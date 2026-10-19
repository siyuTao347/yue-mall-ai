package com.example.agent.customer.outbound;

import api.agent.AgentActor;
import com.example.agent.customer.CustomerDatabase;
import com.example.agent.customer.conversation.CustomerConversationService;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

import static com.example.agent.customer.CustomerJson.*;

@Service
public class CustomerOutboundService {

    private static final Logger log = LoggerFactory.getLogger(CustomerOutboundService.class);

    private final CustomerDatabase db;
    private final RecipientResolver recipientResolver;
    private final CustomerConversationService conversationService;

    public CustomerOutboundService(CustomerDatabase db,
                                   RecipientResolver recipientResolver,
                                   CustomerConversationService conversationService) {
        this.db = db;
        this.recipientResolver = recipientResolver;
        this.conversationService = conversationService;
    }

    public record IngestResult(String receiptNo, String status, boolean duplicate) {}

    public IngestResult ingestEvent(String source, JsonNode rootNode, String rawBody) {
        String eventId = rootNode.path("eventId").asText("");
        String eventType = rootNode.path("eventType").asText("");
        String aggType = rootNode.path("aggregateType").asText("ORDER");
        String aggId = rootNode.path("aggregateId").asText("");
        long aggVersion = rootNode.path("aggregateVersion").asLong(1L);

        // 1. Inbox 幂等插入
        String receiptNo = id("EIN");
        String payloadHash = hash(rawBody);

        try {
            db.mysql.update(
                    "INSERT INTO t_agent_event_inbox(receipt_no, source, event_id, event_type, aggregate_type, " +
                            "aggregate_id, aggregate_version, payload_hash, verified_payload_json, state) " +
                            "VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, 'RECEIVED')",
                    receiptNo, source, eventId, eventType, aggType, aggId, aggVersion, payloadHash, write(rootNode)
            );
        } catch (Exception duplicateInbox) {
            log.info("Duplicate inbound event: source={}, eventId={}", source, eventId);
            return new IngestResult(receiptNo, "DUPLICATE", true);
        }

        // 2. 异步推进触达决策
        processEventAsync(receiptNo, source, eventType, aggType, aggId, rootNode);

        return new IngestResult(receiptNo, "ACCEPTED", false);
    }

    private void processEventAsync(String receiptNo, String source, String eventType, String aggType, String aggId, JsonNode eventData) {
        Thread.startVirtualThread(() -> {
            try {
                // 解析受信任接收人
                Long recipientId = recipientResolver.resolveOrderRecipient(aggId);
                if (recipientId == null || recipientId <= 0) {
                    db.mysql.update("UPDATE t_agent_event_inbox SET state = 'SKIPPED', decision_code = 'NO_RECIPIENT' WHERE receipt_no = ?", receiptNo);
                    return;
                }

                // 业务级防重复触达校验
                String businessDedupeKey = eventType + ":" + aggId + ":" + recipientId;
                String deliveryNo = id("OD");
                try {
                    db.mysql.update(
                            "INSERT INTO t_agent_outbound_delivery(delivery_no, inbox_id, recipient_user_id, " +
                                    "business_dedupe_key, decision, delivery_state, rule_code, rule_version, template_code, template_version) " +
                                    "VALUES(?, 1, ?, ?, 'SENT', 'PROCESSING', 'RULE_DEFAULT', 1, 'TPL_SERVICE_NOTICE', 1)",
                            deliveryNo, recipientId, businessDedupeKey
                    );
                } catch (Exception duplicateDelivery) {
                    db.mysql.update("UPDATE t_agent_event_inbox SET state = 'SKIPPED', decision_code = 'BUSINESS_DUPLICATE' WHERE receipt_no = ?", receiptNo);
                    return;
                }

                // 寻找或创建会话绑定
                String sessionNo = getOrCreateBoundSession(recipientId, aggType, aggId);

                // 发送服务通知消息
                String noticeContent = formatEventNotice(eventType, aggId, eventData);
                AgentActor systemActor = new AgentActor(recipientId, "USER");
                var receipt = conversationService.sendMessage(
                        systemActor, sessionNo, "evt-" + receiptNo, noticeContent, null, "OUTBOUND", null
                );

                db.mysql.update("UPDATE t_agent_outbound_delivery SET session_no = ?, message_no = ?, delivery_state = 'SUCCEEDED' WHERE delivery_no = ?",
                        sessionNo, receipt.messageNo(), deliveryNo);
                db.mysql.update("UPDATE t_agent_event_inbox SET state = 'SUCCEEDED', decision_code = 'SENT' WHERE receipt_no = ?", receiptNo);

            } catch (Exception e) {
                log.error("Failed to process outbound event receiptNo={}, err={}", receiptNo, e.getMessage(), e);
                db.mysql.update("UPDATE t_agent_event_inbox SET state = 'RETRY_WAIT' WHERE receipt_no = ?", receiptNo);
            }
        });
    }

    private String getOrCreateBoundSession(long userId, String bizType, String bizNo) {
        List<String> sessions = db.mysql.queryForList(
                "SELECT session_no FROM t_agent_business_session_binding WHERE user_id = ? AND biz_type = ? AND biz_no = ?",
                String.class, userId, bizType, bizNo
        );
        if (!sessions.isEmpty()) {
            return sessions.getFirst();
        }
        String sessionNo = id("AS");
        db.mysql.update(
                "INSERT INTO t_agent_session(session_no, user_id, user_role, status, service_mode, origin, last_active_time) " +
                        "VALUES(?, ?, 'USER', 'ACTIVE', 'BOT', 'OUTBOUND', now(3))",
                sessionNo, userId
        );
        db.mysql.update(
                "INSERT INTO t_agent_business_session_binding(user_id, biz_type, biz_no, session_no) VALUES(?, ?, ?, ?) " +
                        "ON DUPLICATE KEY UPDATE session_no = ?",
                userId, bizType, bizNo, sessionNo, sessionNo
        );
        return sessionNo;
    }

    private String formatEventNotice(String eventType, String bizNo, JsonNode eventData) {
        return switch (eventType) {
            case "LOGISTICS_DELAYED" -> "【服务通知】检测到您的订单 " + bizNo + " 配送状态出现异常延误，如有疑问请在此随时咨询客服。";
            case "REFUND_SUCCEEDED" -> "【服务通知】您的订单 " + bizNo + " 退款已成功处理，款项将按原支付渠道退回。";
            case "AFTER_SALE_STATUS_CHANGED" -> "【服务通知】您的售后申请（订单 " + bizNo + "）状态已发生变更，请进入详情查看最新进度。";
            default -> "【服务通知】订单 " + bizNo + " 有新的状态更新。";
        };
    }
}
