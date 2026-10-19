package com.example.agent.customer.delivery;

import com.example.agent.customer.CustomerDatabase;
import com.example.agent.customer.realtime.RealtimeProtocol;
import com.example.agent.customer.realtime.RealtimeSubscriptionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

import static com.example.agent.customer.CustomerJson.*;

@Service
public class CustomerEventDispatcher {

    private static final Logger log = LoggerFactory.getLogger(CustomerEventDispatcher.class);
    private static final String REDIS_CHANNEL_PREFIX = "agent:event:notify:";

    private final CustomerDatabase db;
    private final RealtimeSubscriptionService realtimeSubscriptions;
    private final StringRedisTemplate redis;

    @Autowired
    public CustomerEventDispatcher(CustomerDatabase db,
                                   RealtimeSubscriptionService realtimeSubscriptions,
                                   @Autowired(required = false) StringRedisTemplate redis) {
        this.db = db;
        this.realtimeSubscriptions = realtimeSubscriptions;
        this.redis = redis;
    }

    /**
     * Outbox 投递调度：扫描并分发待处理事件
     */
    @Scheduled(fixedDelay = 1000)
    public void dispatchPendingOutboxEvents() {
        try {
            List<Map<String, Object>> pending = db.mysql.queryForList(
                    "SELECT o.id, o.event_id, o.session_no, o.event_seq, o.event_type, e.public_payload_json " +
                            "FROM t_agent_event_outbox o " +
                            "JOIN t_agent_session_event e ON e.event_id = o.event_id " +
                            "WHERE o.state = 'PENDING' AND o.next_retry_time <= now(3) " +
                            "ORDER BY o.id ASC LIMIT 50"
            );
            for (var row : pending) {
                dispatchSingleEvent(row);
            }
        } catch (Exception e) {
            log.error("dispatchPendingOutboxEvents error: {}", e.getMessage());
        }
    }

    private void dispatchSingleEvent(Map<String, Object> row) {
        long outboxId = ((Number) row.get("id")).longValue();
        String sessionNo = String.valueOf(row.get("session_no"));
        long eventSeq = ((Number) row.get("event_seq")).longValue();
        String eventType = String.valueOf(row.get("event_type"));
        JsonNode payload = read(row.get("public_payload_json"));

        ObjectNode envelope = RealtimeProtocol.envelope(eventType, sessionNo, payload);
        envelope.put("eventSeq", eventSeq);
        envelope.put("eventId", String.valueOf(row.get("event_id")));

        try {
            // 1. 本地实例实时推送给订阅者
            realtimeSubscriptions.broadcastToSession(sessionNo, envelope);

            // 2. Redis 广播多实例轻量通知
            if (redis != null) {
                ObjectNode notifyMsg = object().put("sessionNo", sessionNo).put("eventSeq", eventSeq);
                redis.convertAndSend(REDIS_CHANNEL_PREFIX + sessionNo, write(notifyMsg));
            }

            // 3. 标记 Outbox 成功
            db.mysql.update(
                    "UPDATE t_agent_event_outbox SET state = 'SUCCEEDED', finished_time = now(3) WHERE id = ?",
                    outboxId
            );
        } catch (Exception e) {
            log.warn("Event dispatch failed for outboxId={}, err={}", outboxId, e.getMessage());
            db.mysql.update(
                    "UPDATE t_agent_event_outbox SET attempt_count = attempt_count + 1, " +
                            "next_retry_time = TIMESTAMPADD(SECOND, 5, now(3)), last_error_code = 'DISPATCH_FAILED' WHERE id = ?",
                    outboxId
            );
        }
    }
}
