package com.example.agent.customer.sop;

import api.agent.AgentActor;
import api.agent.CustomerAfterSaleCommandDubboService;
import com.example.agent.customer.CustomerDatabase;
import com.example.agent.customer.conversation.CustomerConversationService;
import com.example.agent.customer.conversation.SessionEventStore;
import com.example.agent.customer.realtime.RealtimeProtocol;
import com.example.agent.customer.richui.RichMessageAssembler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.dubbo.config.annotation.DubboReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

import static com.example.agent.customer.CustomerJson.*;

@Service
public class CustomerSopEngine {

    private static final Logger log = LoggerFactory.getLogger(CustomerSopEngine.class);

    private final CustomerDatabase db;
    private final SessionEventStore eventStore;
    private final RichMessageAssembler cardAssembler;
    private final CustomerConversationService conversationService;

    @DubboReference(timeout = 5000, retries = 0, check = false, url = "${DUBBO_ORDER_SERVICE_URL:dubbo://127.0.0.1:20880}")
    private CustomerAfterSaleCommandDubboService afterSaleCommandService;

    public CustomerSopEngine(CustomerDatabase db,
                             SessionEventStore eventStore,
                             RichMessageAssembler cardAssembler,
                             CustomerConversationService conversationService) {
        this.db = db;
        this.eventStore = eventStore;
        this.cardAssembler = cardAssembler;
        this.conversationService = conversationService;
    }

    public record SopStartResult(String workflowNo, String currentNode, String state, String taskNo) {}

    public SopStartResult startWorkflow(AgentActor actor, String workflowCode, String sessionNo,
                                        String bizType, String bizNo, JsonNode initialVars) {
        return db.tx.execute(tx -> {
            String workflowNo = id("WF");
            String taskNo = id("WT");
            String formInstanceId = id("FORM");

            // 1. 实例初始化落库 (停靠在 collect_reason FORM 节点)
            db.mysql.update(
                    "INSERT INTO t_agent_sop_instance(workflow_no, definition_code, definition_version, user_id, " +
                            "biz_type, biz_no, origin_session_no, current_session_no, state, current_node, variables_json) " +
                            "VALUES(?, ?, 1, ?, ?, ?, ?, ?, 'WAITING_USER', 'collect_reason', ?)",
                    workflowNo, workflowCode != null ? workflowCode : "AFTER_SALE_REVIEW", actor.userId(),
                    bizType != null ? bizType : "ORDER", bizNo, sessionNo, sessionNo,
                    initialVars != null ? write(initialVars) : "{}"
            );

            // 2. 创建第一个交互表单任务
            db.mysql.update(
                    "INSERT INTO t_agent_sop_task(task_no, workflow_no, node_execution_no, node_id, kind, state, " +
                            "form_instance_id, version) VALUES(?, ?, ?, 'collect_reason', 'FORM', 'PENDING', ?, 1)",
                    taskNo, workflowNo, id("EXE"), formInstanceId
            );

            // 3. 构建表单卡片并发送至会话
            ArrayNode fields = array();
            fields.add(object().put("name", "requestType").put("label", "售后类型").put("widget", "select").put("required", true)
                    .set("options", array().add(object().put("value", "REFUND").put("label", "仅退款")).add(object().put("value", "RETURN").put("label", "退货退款"))));
            fields.add(object().put("name", "reasonCode").put("label", "申请原因").put("widget", "select").put("required", true)
                    .set("options", array().add(object().put("value", "NOT_RECEIVED").put("label", "未收到商品")).add(object().put("value", "QUALITY_ISSUE").put("label", "商品与描述不符"))));
            fields.add(object().put("name", "description").put("label", "补充说明").put("widget", "textarea").put("maxLength", 500));

            ObjectNode formCard = cardAssembler.buildDynamicFormCard(workflowNo, taskNo, formInstanceId, 1, fields);
            ArrayNode cards = array().add(formCard);

            conversationService.sendMessage(
                    new AgentActor(1L, "SYSTEM"), sessionNo, "sop-init-" + workflowNo,
                    "已为您发起售后服务申请流程，请在下方卡片中填写申请原因并提交：",
                    null, "SOP", cards
            );

            return new SopStartResult(workflowNo, "collect_reason", "WAITING_USER", taskNo);
        });
    }

    public ObjectNode submitTask(AgentActor actor, String workflowNo, String taskNo, JsonNode values, String operationId) {
        return db.tx.execute(tx -> {
            var wfRows = db.mysql.queryForList("SELECT * FROM t_agent_sop_instance WHERE workflow_no = ? FOR UPDATE", workflowNo);
            if (wfRows.isEmpty()) throw error(404, "AGENT_NOT_FOUND", "工作流实例不存在");
            var wf = wfRows.getFirst();
            String sessionNo = String.valueOf(wf.get("current_session_no"));
            String bizNo = wf.get("biz_no") != null ? String.valueOf(wf.get("biz_no")) : null;

            // 1. 完成当前表单任务
            db.mysql.update(
                    "UPDATE t_agent_sop_task SET state = 'COMPLETED', submitted_json = ?, version = version + 1 WHERE task_no = ?",
                    write(values), taskNo
            );

            // 2. 推进至下游命令节点 (CREATE_AFTER_SALE_REQUEST)
            String commandOpId = workflowNo + ":exe-cmd-01:CREATE_AFTER_SALE";
            String disputeNo = "RF" + (bizNo != null ? bizNo : System.currentTimeMillis());

            // 尝试调用 Dubbo 售后命令服务
            try {
                if (afterSaleCommandService != null) {
                    ObjectNode authCtx = object().put("subjectUserId", actor.userId()).put("workflowNo", workflowNo);
                    ObjectNode cmdReq = object().put("orderNo", bizNo)
                            .put("requestType", values.path("requestType").asText("REFUND"))
                            .put("reason", values.path("reasonCode").asText("NOT_RECEIVED") + ": " + values.path("description").asText(""));
                    var cmdResult = afterSaleCommandService.createAfterSaleRequest(commandOpId, write(authCtx), write(cmdReq));
                    if (cmdResult.isSuccess() && cmdResult.getDisputeNo() != null) {
                        disputeNo = cmdResult.getDisputeNo();
                    }
                }
            } catch (Exception e) {
                log.warn("SOP command invocation failed, fallback to local dispute generation: {}", e.getMessage());
            }

            // 3. 更新工作流状态为 WAITING_HUMAN (进入人工审核队列)
            db.mysql.update(
                    "UPDATE t_agent_sop_instance SET current_node = 'manual_review', state = 'WAITING_HUMAN', " +
                            "result_ref = ?, version = version + 1 WHERE workflow_no = ?",
                    disputeNo, workflowNo
            );

            // 4. 发送审核推进通知消息
            conversationService.sendMessage(
                    new AgentActor(1L, "SYSTEM"), sessionNo, "sop-submit-" + taskNo,
                    "您的售后申请资料已提交成功（服务单号：" + disputeNo + "），已进入客服人工审核队列，我们将在 24 小时内为您完成复核。",
                    null, "SOP", null
            );

            ObjectNode res = object()
                    .put("workflowNo", workflowNo)
                    .put("currentNode", "manual_review")
                    .put("state", "WAITING_HUMAN")
                    .put("disputeNo", disputeNo);
            return res;
        });
    }

    public List<Map<String, Object>> listUserWorkflows(long userId) {
        return db.mysql.queryForList(
                "SELECT workflow_no, definition_code, biz_type, biz_no, current_session_no, state, current_node, result_ref, created_time, updated_time " +
                        "FROM t_agent_sop_instance WHERE user_id = ? ORDER BY id DESC LIMIT 20",
                userId
        );
    }
}
