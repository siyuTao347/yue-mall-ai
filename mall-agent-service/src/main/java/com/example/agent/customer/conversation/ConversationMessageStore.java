package com.example.agent.customer.conversation;

import com.example.agent.customer.CustomerDatabase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

import static com.example.agent.customer.CustomerJson.*;

@Repository
public class ConversationMessageStore {

    private final CustomerDatabase db;

    public ConversationMessageStore(CustomerDatabase db) {
        this.db = db;
    }

    public void insertMessageInTx(String messageNo, String sessionNo, long sessionSeq, String runNo,
                                  String clientId, String senderType, String senderId, String role,
                                  String content, JsonNode payload, int schemaVersion, String outputKind, String requestHash) {
        db.mysql.update(
                "INSERT INTO t_agent_message(message_no, session_no, session_seq, run_no, client_message_id, " +
                        "role, sender_type, sender_id, content, payload_json, schema_version, output_kind, request_hash) " +
                        "VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                messageNo, sessionNo, sessionSeq, runNo, clientId, role, senderType, senderId,
                content, payload != null ? write(payload) : null, schemaVersion, outputKind, requestHash
        );
        db.mysql.update(
                "UPDATE t_agent_session SET last_public_message_seq = ?, message_count = message_count + 1, " +
                        "last_active_time = now(3) WHERE session_no = ?",
                sessionSeq, sessionNo
        );
    }

    public List<Map<String, Object>> listMessages(String sessionNo, Long beforeMessageSeq, int limit) {
        int boundedLimit = Math.clamp(limit, 1, 100);
        String sql = beforeMessageSeq != null && beforeMessageSeq > 0
                ? "SELECT * FROM t_agent_message WHERE session_no = ? AND session_seq < ? ORDER BY session_seq DESC LIMIT ?"
                : "SELECT * FROM t_agent_message WHERE session_no = ? ORDER BY session_seq DESC LIMIT ?";
        List<Map<String, Object>> rows = beforeMessageSeq != null && beforeMessageSeq > 0
                ? db.mysql.queryForList(sql, sessionNo, beforeMessageSeq, boundedLimit)
                : db.mysql.queryForList(sql, sessionNo, boundedLimit);
        // Reverse so that the returned order is chronological
        return rows.reversed();
    }

    public ObjectNode getSessionSnapshot(String sessionNo) {
        var s = db.mysql.queryForMap(
                "SELECT session_no, status, service_mode, control_epoch, last_event_seq, last_public_message_seq, " +
                        "active_handoff_no, assigned_staff_id, summary, slot_json " +
                        "FROM t_agent_session WHERE session_no = ?",
                sessionNo
        );
        ObjectNode result = object();
        result.put("sessionNo", sessionNo);
        result.put("status", String.valueOf(s.get("status")));
        result.put("serviceMode", s.get("service_mode") != null ? String.valueOf(s.get("service_mode")) : "BOT");
        result.put("controlEpoch", s.get("control_epoch") != null ? ((Number) s.get("control_epoch")).longValue() : 1L);
        result.put("snapshotSeq", s.get("last_event_seq") != null ? ((Number) s.get("last_event_seq")).longValue() : 0L);
        result.put("lastPublicMessageSeq", s.get("last_public_message_seq") != null ? ((Number) s.get("last_public_message_seq")).longValue() : 0L);
        result.put("activeHandoffNo", s.get("active_handoff_no") != null ? String.valueOf(s.get("active_handoff_no")) : null);
        result.put("assignedStaffId", s.get("assigned_staff_id") != null ? ((Number) s.get("assigned_staff_id")).longValue() : null);

        // Fetch recent messages
        ArrayNode msgs = array();
        for (var row : listMessages(sessionNo, null, 20)) {
            ObjectNode m = object();
            m.put("messageNo", String.valueOf(row.get("message_no")));
            m.put("sessionSeq", row.get("session_seq") != null ? ((Number) row.get("session_seq")).longValue() : 0L);
            m.put("senderType", String.valueOf(row.get("sender_type")));
            m.put("role", String.valueOf(row.get("role")));
            m.put("content", String.valueOf(row.get("content")));
            if (row.get("payload_json") != null) {
                m.set("payload", read(row.get("payload_json")));
            }
            msgs.add(m);
        }
        result.set("recentMessages", msgs);
        return result;
    }
}
