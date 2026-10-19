package com.example.agent.customer.conversation;

import com.example.agent.customer.CustomerDatabase;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

import static com.example.agent.customer.CustomerJson.*;

@Repository
public class SessionEventStore {

    private final CustomerDatabase db;

    public SessionEventStore(CustomerDatabase db) {
        this.db = db;
    }

    /**
     * 事务内分配下一个连续 eventSeq 并同时落库 SessionEvent 与 Outbox
     */
    public long appendPublicEventInTx(String sessionNo, String eventType, JsonNode publicPayload) {
        // 1. 锁会话行并递增分配下一个 eventSeq
        var sessionRow = db.mysql.queryForMap(
                "SELECT last_event_seq FROM t_agent_session WHERE session_no = ? FOR UPDATE",
                sessionNo
        );
        long currentSeq = sessionRow.get("last_event_seq") != null ? ((Number) sessionRow.get("last_event_seq")).longValue() : 0L;
        long nextSeq = currentSeq + 1L;

        String eventId = id("EV");
        String payloadStr = write(publicPayload);

        // 2. 插入公开事件表
        db.mysql.update(
                "INSERT INTO t_agent_session_event(event_id, session_no, event_seq, event_type, public_payload_json) VALUES(?, ?, ?, ?, ?)",
                eventId, sessionNo, nextSeq, eventType, payloadStr
        );

        // 3. 同步写入 Outbox 待发布队列
        db.mysql.update(
                "INSERT INTO t_agent_event_outbox(event_id, session_no, event_seq, event_type, state) VALUES(?, ?, ?, ?, 'PENDING')",
                eventId, sessionNo, nextSeq, eventType
        );

        // 4. 更新会话高水位
        db.mysql.update(
                "UPDATE t_agent_session SET last_event_seq = ?, updated_time = now(3) WHERE session_no = ?",
                nextSeq, sessionNo
        );

        return nextSeq;
    }

    public List<Map<String, Object>> listEventsAfter(String sessionNo, long afterEventSeq, int limit) {
        int boundedLimit = Math.clamp(limit, 1, 200);
        return db.mysql.queryForList(
                "SELECT event_id, session_no, event_seq, event_type, public_payload_json, created_time " +
                        "FROM t_agent_session_event WHERE session_no = ? AND event_seq > ? ORDER BY event_seq ASC LIMIT ?",
                sessionNo, afterEventSeq, boundedLimit
        );
    }

    public long getHighWatermark(String sessionNo) {
        List<Long> seqs = db.mysql.queryForList(
                "SELECT last_event_seq FROM t_agent_session WHERE session_no = ?",
                Long.class, sessionNo
        );
        return seqs.isEmpty() || seqs.getFirst() == null ? 0L : seqs.getFirst();
    }
}
