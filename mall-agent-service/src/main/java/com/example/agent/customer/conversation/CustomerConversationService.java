package com.example.agent.customer.conversation;

import api.agent.AgentActor;
import com.example.agent.customer.CustomerDatabase;
import com.example.agent.customer.delivery.CustomerEventDispatcher;
import com.example.agent.customer.realtime.RealtimeProtocol;
import com.example.agent.customer.realtime.RealtimeSubscriptionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static com.example.agent.customer.CustomerJson.*;

@Service
public class CustomerConversationService {

    private static final Logger log = LoggerFactory.getLogger(CustomerConversationService.class);

    private final CustomerDatabase db;
    private final SessionEventStore eventStore;
    private final ConversationMessageStore messageStore;
    private final CustomerEventDispatcher dispatcher;
    private final RealtimeSubscriptionService subscriptionService;

    public CustomerConversationService(CustomerDatabase db,
                                       SessionEventStore eventStore,
                                       ConversationMessageStore messageStore,
                                       CustomerEventDispatcher dispatcher,
                                       RealtimeSubscriptionService subscriptionService) {
        this.db = db;
        this.eventStore = eventStore;
        this.messageStore = messageStore;
        this.dispatcher = dispatcher;
        this.subscriptionService = subscriptionService;
    }

    public record SendReceipt(String messageNo, long eventSeq, String sessionNo, boolean replayed, String serviceMode, long controlEpoch) {}

    /**
     * 统一消息接收与持久化，支持用户与人工坐席发送
     */
    public SendReceipt sendMessage(AgentActor actor, String sessionNo, String clientMessageId, String content,
                                   Long expectedControlEpoch, String senderType, JsonNode cards) {
        if (content == null || content.isBlank() || content.length() > 2000) {
            throw error(400, "AGENT_MESSAGE_INVALID", "消息须为 1–2000 个字符");
        }
        if (clientMessageId == null || !clientMessageId.matches("[A-Za-z0-9_-]{1,64}")) {
            throw error(400, "AGENT_MESSAGE_INVALID", "消息标识不合法");
        }
        String effectiveSenderType = senderType != null ? senderType : ("ADMIN".equalsIgnoreCase(actor.role()) ? "STAFF" : "USER");
        String requestHash = hash(content + ":" + effectiveSenderType);
        String scopeKey = "SESSION:" + sessionNo;

        return db.tx.execute(tx -> {
            // 1. 锁会话行
            var sessionRows = db.mysql.queryForList(
                    "SELECT *, last_active_time < DATE_SUB(now(3), INTERVAL 24 HOUR) AS expired " +
                            "FROM t_agent_session WHERE session_no = ? FOR UPDATE",
                    sessionNo
            );
            if (sessionRows.isEmpty()) {
                throw error(403, "AGENT_PERMISSION_DENIED", "会话不存在或无权访问");
            }
            var s = sessionRows.getFirst();
            long sessionUserId = ((Number) s.get("user_id")).longValue();
            String status = String.valueOf(s.get("status"));
            String serviceMode = s.get("service_mode") != null ? String.valueOf(s.get("service_mode")) : "BOT";
            long currentEpoch = s.get("control_epoch") != null ? ((Number) s.get("control_epoch")).longValue() : 1L;

            // 权限校验
            boolean isStaff = "STAFF".equalsIgnoreCase(effectiveSenderType) || "ADMIN".equalsIgnoreCase(actor.role());
            if (!isStaff && actor.userId() != sessionUserId) {
                throw error(403, "AGENT_PERMISSION_DENIED", "无法向他人会话发送消息");
            }
            if ("CLOSED".equals(status) || "EXPIRED".equals(status)) {
                throw error(409, "AGENT_SESSION_INACTIVE", "会话已结束，无法继续发送");
            }

            // 控制权代际防迟到覆盖校验
            if (expectedControlEpoch != null && expectedControlEpoch < currentEpoch) {
                throw error(409, "CONTROL_EPOCH_STALE", "会话控制权已转移，无法使用过时代际发送");
            }

            // 2. 幂等校验 (t_agent_command_operation)
            String opActorType = isStaff ? "STAFF" : "USER";
            String opActorId = String.valueOf(actor.userId());
            var opList = db.mysql.queryForList(
                    "SELECT operation_no, request_hash, state, receipt_json FROM t_agent_command_operation " +
                            "WHERE actor_type = ? AND actor_id = ? AND scope_key = ? AND client_operation_id = ?",
                    opActorType, opActorId, scopeKey, clientMessageId
            );
            if (!opList.isEmpty()) {
                var prevOp = opList.getFirst();
                if (!requestHash.equals(prevOp.get("request_hash"))) {
                    throw error(409, "IDEMPOTENCY_PAYLOAD_CONFLICT", "消息标识已被其他内容使用");
                }
                JsonNode receiptJson = read(prevOp.get("receipt_json"));
                return new SendReceipt(
                        receiptJson.path("messageNo").asText(),
                        receiptJson.path("eventSeq").asLong(),
                        sessionNo,
                        true,
                        serviceMode,
                        currentEpoch
                );
            }

            // 3. 生成 Message 与公开事件
            String messageNo = id("AM");
            String role = isStaff ? "STAFF" : "USER";
            String displayName = isStaff ? "客服人员" : "我";

            ObjectNode eventPayload = object();
            eventPayload.put("messageNo", messageNo);
            eventPayload.put("clientMessageId", clientMessageId);
            eventPayload.set("sender", object().put("type", effectiveSenderType).put("displayName", displayName));
            eventPayload.put("content", content);
            eventPayload.put("schemaVersion", RealtimeProtocol.MESSAGE_SCHEMA_VERSION);
            eventPayload.set("cards", cards != null && cards.isArray() ? cards : array());

            // 同事务分配 eventSeq、落库公开事件表和 Outbox
            long eventSeq = eventStore.appendPublicEventInTx(sessionNo, RealtimeProtocol.TYPE_MESSAGE_CREATED, eventPayload);

            // 落库消息实体
            messageStore.insertMessageInTx(
                    messageNo, sessionNo, eventSeq, null, clientMessageId,
                    effectiveSenderType, String.valueOf(actor.userId()), role,
                    content, cards, RealtimeProtocol.MESSAGE_SCHEMA_VERSION, "FINAL", requestHash
            );

            // 更新用户收件箱版本指针 (未读红点加速)
            db.mysql.update(
                    "INSERT INTO t_agent_inbox_head(user_id, revision, updated_time) VALUES(?, 1, now(3)) " +
                            "ON DUPLICATE KEY UPDATE revision = revision + 1, updated_time = now(3)",
                    sessionUserId
            );

            // 4. 记录命令操作幂等记录 (保留 7 天)
            String opNo = id("OP");
            ObjectNode receipt = object()
                    .put("messageNo", messageNo)
                    .put("eventSeq", eventSeq)
                    .put("sessionNo", sessionNo)
                    .put("replayed", false);
            db.mysql.update(
                    "INSERT INTO t_agent_command_operation(operation_no, actor_type, actor_id, scope_key, " +
                            "client_operation_id, request_hash, state, receipt_json, result_ref, expiry_time) " +
                            "VALUES(?, ?, ?, ?, ?, ?, 'SUCCEEDED', ?, ?, TIMESTAMPADD(DAY, 7, now(3)))",
                    opNo, opActorType, opActorId, scopeKey, clientMessageId, requestHash, write(receipt), messageNo
            );

            return new SendReceipt(messageNo, eventSeq, sessionNo, false, serviceMode, currentEpoch);
        });
    }

    /**
     * 确认已读标记
     */
    public void markRead(AgentActor actor, String sessionNo, long lastReadMessageSeq) {
        String actorType = "ADMIN".equalsIgnoreCase(actor.role()) ? "STAFF" : "USER";
        db.mysql.update(
                "INSERT INTO t_agent_read_cursor(session_no, actor_type, actor_id, last_read_message_seq, updated_time) " +
                        "VALUES(?, ?, ?, ?, now(3)) " +
                        "ON DUPLICATE KEY UPDATE last_read_message_seq = GREATEST(last_read_message_seq, ?), updated_time = now(3)",
                sessionNo, actorType, actor.userId(), lastReadMessageSeq, lastReadMessageSeq
        );
    }
}
