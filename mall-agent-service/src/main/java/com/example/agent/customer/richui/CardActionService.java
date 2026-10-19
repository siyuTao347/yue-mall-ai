package com.example.agent.customer.richui;

import api.agent.AgentActor;
import com.example.agent.customer.CustomerDatabase;
import com.example.agent.customer.conversation.SessionEventStore;
import com.example.agent.customer.realtime.RealtimeProtocol;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import static com.example.agent.customer.CustomerJson.*;

@Service
public class CardActionService {

    private final CustomerDatabase db;
    private final SessionEventStore eventStore;

    public CardActionService(CustomerDatabase db, SessionEventStore eventStore) {
        this.db = db;
        this.eventStore = eventStore;
    }

    public ObjectNode executeAction(AgentActor actor, String sessionNo, String cardId, String actionId,
                                    int revision, String type, JsonNode input, String operationId) {
        return db.tx.execute(tx -> {
            var sessionRows = db.mysql.queryForList("SELECT * FROM t_agent_session WHERE session_no = ? FOR UPDATE", sessionNo);
            if (sessionRows.isEmpty()) throw error(404, "AGENT_NOT_FOUND", "会话不存在");

            ObjectNode result = object();
            result.put("cardId", cardId);
            result.put("status", "SUCCEEDED");
            int nextRevision = revision + 1;
            result.put("revision", nextRevision);

            switch (type) {
                case "SUBMIT_RATING" -> {
                    int rating = input.path("rating").asInt(5);
                    String comment = input.path("comment").asText("");
                    String scope = input.path("feedbackScope").asText("RUN");
                    String targetNo = input.path("targetNo").asText(sessionNo);

                    db.mysql.update(
                            "INSERT INTO t_agent_feedback(session_no, feedback_scope, target_no, user_id, rating, comment) " +
                                    "VALUES(?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE rating = ?, comment = ?",
                            sessionNo, scope, targetNo, actor.userId(), rating, redact(comment), rating, redact(comment)
                    );
                    result.put("message", "评价提交成功，感谢反馈！");
                }
                case "CANCEL_HANDOFF" -> {
                    // 取消当前排队
                    db.mysql.update("UPDATE t_agent_handoff SET status = 'CLOSED' WHERE session_no = ? AND status = 'OPEN'", sessionNo);
                    db.mysql.update("UPDATE t_agent_session SET service_mode = 'BOT', status = 'ACTIVE', active_handoff_no = NULL WHERE session_no = ?", sessionNo);
                    result.put("message", "已取消排队，已为您切回智能客服。");
                }
                default -> {
                    result.put("message", "操作已处理完成。");
                }
            }

            // 广播 card.updated 事件
            ObjectNode cardEvent = object()
                    .put("cardId", cardId)
                    .put("revision", nextRevision)
                    .put("state", "SUBMITTED")
                    .set("result", result);
            eventStore.appendPublicEventInTx(sessionNo, RealtimeProtocol.TYPE_CARD_UPDATED, cardEvent);

            return result;
        });
    }
}
