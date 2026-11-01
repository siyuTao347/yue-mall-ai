package com.example.agent.customer.richui;

import api.agent.AgentActor;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.agent.customer.CustomerDatabase;
import com.example.agent.customer.conversation.CustomerSessionAccessService;
import com.example.agent.customer.conversation.SessionEventStore;
import com.example.agent.customer.money.AgentMoneyCardEntity;
import com.example.agent.customer.money.AgentMoneyCardMapper;
import com.example.agent.customer.realtime.RealtimeProtocol;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;

import static com.example.agent.customer.CustomerJson.*;

@Service
public class CardActionService {

    private final CustomerDatabase db;
    private final SessionEventStore eventStore;
    private final CustomerSessionAccessService sessionAccessService;
    private final AgentMoneyCardMapper cardMapper;
    private final ObjectMapper objectMapper;

    public CardActionService(CustomerDatabase db,
                             SessionEventStore eventStore,
                             CustomerSessionAccessService sessionAccessService,
                             AgentMoneyCardMapper cardMapper,
                             ObjectMapper objectMapper) {
        this.db = db;
        this.eventStore = eventStore;
        this.sessionAccessService = sessionAccessService;
        this.cardMapper = cardMapper;
        this.objectMapper = objectMapper;
    }

    public CardActionService(CustomerDatabase db,
                             SessionEventStore eventStore,
                             CustomerSessionAccessService sessionAccessService,
                             AgentMoneyCardMapper cardMapper) {
        this(db, eventStore, sessionAccessService, cardMapper, new ObjectMapper());
    }

    public ObjectNode executeAction(AgentActor actor, String sessionNo, String cardId, String actionId,
                                    int revision, String type, JsonNode input, String operationId) {
        // 1. 严格校验会话所有权与读写权限
        sessionAccessService.validateAndGetSession(actor, sessionNo);

        return db.tx.execute(tx -> {
            var sessionRows = db.mysql.queryForList("SELECT * FROM t_agent_session WHERE session_no = ? FOR UPDATE", sessionNo);
            if (sessionRows.isEmpty()) throw error(404, "AGENT_NOT_FOUND", "会话不存在");

            // 2. 校验持久化卡片记录（若存在）
            AgentMoneyCardEntity persistedCard = null;
            if (cardId != null && !cardId.isBlank() && cardMapper != null) {
                persistedCard = cardMapper.selectOne(new LambdaQueryWrapper<AgentMoneyCardEntity>()
                        .eq(AgentMoneyCardEntity::getCardId, cardId.trim()));
            }

            int nextRevision = revision + 1;
            if (persistedCard != null) {
                // 校验所有者
                if (persistedCard.getUserId() != null && persistedCard.getUserId() != actor.userId() && !"ADMIN".equalsIgnoreCase(actor.role()) && !"STAFF".equalsIgnoreCase(actor.role())) {
                    throw error(403, "AGENT_PERMISSION_DENIED", "无权操作该卡片");
                }
                if (!sessionNo.equals(persistedCard.getSessionNo())) {
                    throw error(403, "AGENT_PERMISSION_DENIED", "卡片会话归属不一致");
                }
                // 校验版本号
                if (persistedCard.getRevision() != null && persistedCard.getRevision().intValue() != revision) {
                    throw error(409, "CARD_VERSION_CONFLICT", "卡片状态已在其他终端更新，请刷新后重试");
                }
                // 校验允许动作
                if (persistedCard.getAllowedActionsJson() != null && !persistedCard.getAllowedActionsJson().isBlank()) {
                    try {
                        JsonNode allowedArr = objectMapper.readTree(persistedCard.getAllowedActionsJson());
                        Set<String> allowedSet = new HashSet<>();
                        if (allowedArr.isArray()) {
                            for (JsonNode a : allowedArr) {
                                if (a.isTextual()) allowedSet.add(a.asText());
                                else if (a.has("type")) allowedSet.add(a.get("type").asText());
                                else if (a.has("actionId")) allowedSet.add(a.get("actionId").asText());
                            }
                        }
                        if (!allowedSet.isEmpty() && !allowedSet.contains(type) && !allowedSet.contains(actionId)) {
                            throw error(403, "AGENT_ACTION_FORBIDDEN", "此卡片不允许执行该动作: " + type);
                        }
                    } catch (Exception ex) {
                        if (ex instanceof RuntimeException re) throw re;
                    }
                }
                nextRevision = persistedCard.getRevision() != null ? persistedCard.getRevision().intValue() + 1 : revision + 1;
                persistedCard.setRevision((long) nextRevision);
                persistedCard.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
                cardMapper.updateById(persistedCard);
            }

            ObjectNode result = object();
            result.put("cardId", cardId);
            result.put("status", "SUCCEEDED");
            result.put("revision", nextRevision);

            switch (type) {
                case "SUBMIT_RATING" -> {
                    int rating = input != null && input.has("rating") ? input.path("rating").asInt(5) : 5;
                    String comment = input != null && input.has("comment") ? input.path("comment").asText("") : "";
                    String scope = input != null && input.has("feedbackScope") ? input.path("feedbackScope").asText("RUN") : "RUN";
                    String targetNo = input != null && input.has("targetNo") ? input.path("targetNo").asText(sessionNo) : sessionNo;

                    db.mysql.update(
                            "INSERT INTO t_agent_feedback(session_no, feedback_scope, target_no, user_id, rating, comment) " +
                                    "VALUES(?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE rating = ?, comment = ?",
                            sessionNo, scope, targetNo, actor.userId(), rating, redact(comment), rating, redact(comment)
                    );
                    result.put("message", "评价提交成功，感谢反馈！");
                }
                case "CANCEL_HANDOFF" -> {
                    db.mysql.update("UPDATE t_agent_handoff SET status = 'CLOSED' WHERE session_no = ? AND status = 'OPEN'", sessionNo);
                    db.mysql.update("UPDATE t_agent_session SET service_mode = 'BOT', status = 'ACTIVE', active_handoff_no = NULL WHERE session_no = ?", sessionNo);
                    result.put("message", "已取消排队，已为您切回智能客服。");
                }
                case "OPEN_MONEY_CASE" -> {
                    String caseNo = input != null && input.has("caseNo") ? input.path("caseNo").asText("") : "";
                    result.put("message", "正在查看资金调查案件: " + caseNo);
                    result.put("caseNo", caseNo);
                }
                case "PROVIDE_MONEY_INPUT", "SUBMIT_MONEY_INPUT" -> {
                    result.put("message", "补充资料已提交受理，后台正同步核查。");
                }
                case "CANCEL_MONEY_CASE" -> {
                    result.put("message", "调查取消请求已受理。");
                }
                default -> {
                    // 拒绝未知动作，严禁默认成功
                    throw error(400, "AGENT_ACTION_UNSUPPORTED", "不支持的卡片动作: " + type);
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
