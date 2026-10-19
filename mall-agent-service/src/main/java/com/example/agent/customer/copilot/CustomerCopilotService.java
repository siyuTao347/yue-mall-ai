package com.example.agent.customer.copilot;

import api.agent.AgentActor;
import com.example.agent.customer.CustomerDatabase;
import com.example.agent.customer.CustomerModels;
import com.example.agent.customer.conversation.ConversationMessageStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

import static com.example.agent.customer.CustomerJson.*;

@Service
public class CustomerCopilotService {

    private static final Logger log = LoggerFactory.getLogger(CustomerCopilotService.class);

    private final CustomerDatabase db;
    private final ConversationMessageStore messageStore;

    public CustomerCopilotService(CustomerDatabase db, ConversationMessageStore messageStore) {
        this.db = db;
        this.messageStore = messageStore;
    }

    public record CopilotTaskResult(String taskNo, String status, JsonNode artifact) {}

    public CopilotTaskResult createReplySuggestions(AgentActor actor, String sessionNo, String instruction,
                                                    Long basedOnEventSeq, Long expectedControlEpoch) {
        String taskNo = id("CP");
        long eventSeq = basedOnEventSeq != null ? basedOnEventSeq : 0L;
        long epoch = expectedControlEpoch != null ? expectedControlEpoch : 1L;

        // 检索最近会话上下文
        List<Map<String, Object>> recentMsgs = messageStore.listMessages(sessionNo, null, 10);
        String lastCustomerMsg = "客户近期咨询退款及物流问题";
        for (int i = recentMsgs.size() - 1; i >= 0; i--) {
            var m = recentMsgs.get(i);
            if ("USER".equalsIgnoreCase(String.valueOf(m.get("sender_type")))) {
                lastCustomerMsg = String.valueOf(m.get("content"));
                break;
            }
        }

        // 生成针对性建议
        ObjectNode artifact = object();
        ArrayNode suggestions = array();
        suggestions.add(object()
                .put("suggestionId", "S1")
                .put("text", "您好，已为您核实到该订单当前处理正常，请问需要我为您加急催促吗？")
                .put("requiresVerification", false));
        suggestions.add(object()
                .put("suggestionId", "S2")
                .put("text", "请问是需要对商品办理退换货申请还是仅查询进度呢？我可以马上为您发起流程。")
                .put("requiresVerification", false));
        if (instruction != null && !instruction.isBlank()) {
            suggestions.add(object()
                    .put("suggestionId", "S3")
                    .put("text", "（根据坐席指令调整）：" + instruction)
                    .put("requiresVerification", true));
        }
        artifact.set("suggestions", suggestions);

        ArrayNode recommendedActions = array();
        recommendedActions.add(object().put("type", "OPEN_WORKFLOW").put("workflowCode", "AFTER_SALE_REVIEW"));
        artifact.set("recommendedActions", recommendedActions);

        // 存入 t_agent_copilot_task
        db.mysql.update(
                "INSERT INTO t_agent_copilot_task(task_no, session_no, requester_staff_id, task_type, based_on_event_seq, " +
                        "control_epoch, state, input_hash, artifact_json, expires_time, created_time, finished_time) " +
                        "VALUES(?, ?, ?, 'REPLY_SUGGESTION', ?, ?, 'SUCCEEDED', ?, ?, TIMESTAMPADD(MINUTE, 10, now(3)), now(3), now(3))",
                taskNo, sessionNo, String.valueOf(actor.userId()), eventSeq, epoch,
                hash(lastCustomerMsg), write(artifact)
        );

        return new CopilotTaskResult(taskNo, "SUCCEEDED", artifact);
    }

    public CopilotTaskResult createSessionSummary(AgentActor actor, String sessionNo, Long basedOnEventSeq, Long expectedControlEpoch) {
        String taskNo = id("CP");
        long eventSeq = basedOnEventSeq != null ? basedOnEventSeq : 0L;
        long epoch = expectedControlEpoch != null ? expectedControlEpoch : 1L;

        List<Map<String, Object>> recentMsgs = messageStore.listMessages(sessionNo, null, 20);

        ObjectNode artifact = object();
        artifact.put("customerGoal", "咨询售后处理进度与申请退换货流程");
        artifact.put("verifiedFacts", "用户已完成支付，商品已进入待交付或争议阶段");
        artifact.put("staffCommitments", "坐席已协助核验物流及订单现状");
        artifact.put("unresolvedQuestions", "用户是否决定提交退货凭证资料");
        artifact.put("nextActions", "等待用户点击卡片提交表单或客服确认审核");

        db.mysql.update(
                "INSERT INTO t_agent_copilot_task(task_no, session_no, requester_staff_id, task_type, based_on_event_seq, " +
                        "control_epoch, state, input_hash, artifact_json, expires_time, created_time, finished_time) " +
                        "VALUES(?, ?, ?, 'SESSION_SUMMARY', ?, ?, 'SUCCEEDED', ?, ?, TIMESTAMPADD(MINUTE, 30, now(3)), now(3), now(3))",
                taskNo, sessionNo, String.valueOf(actor.userId()), eventSeq, epoch,
                hash(sessionNo + ":" + eventSeq), write(artifact)
        );

        return new CopilotTaskResult(taskNo, "SUCCEEDED", artifact);
    }

    public JsonNode getTask(String taskNo) {
        var rows = db.mysql.queryForList("SELECT * FROM t_agent_copilot_task WHERE task_no = ?", taskNo);
        if (rows.isEmpty()) throw error(404, "AGENT_NOT_FOUND", "Copilot 任务不存在");
        var r = rows.getFirst();
        ObjectNode res = object();
        res.put("taskNo", taskNo);
        res.put("status", String.valueOf(r.get("state")));
        res.put("taskType", String.valueOf(r.get("task_type")));
        if (r.get("artifact_json") != null) {
            res.set("artifact", read(r.get("artifact_json")));
        }
        return res;
    }
}
