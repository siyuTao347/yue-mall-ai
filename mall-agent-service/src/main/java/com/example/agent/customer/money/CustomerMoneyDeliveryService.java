package com.example.agent.customer.money;

import api.agent.money.MoneyIdGenerator;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.agent.customer.CustomerDatabase;
import com.example.agent.customer.conversation.SessionEventStore;
import com.example.agent.customer.realtime.RealtimeProtocol;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Map;

import static com.example.agent.customer.CustomerJson.object;

/**
 * 资金调查消息投递与接管控制服务
 * 严格按照投递事务保证：
 * 1. 唯一 delivery 记录去重 (caseNo, projectionRevision, recipientUserId, deliveryKind)
 * 2. 锁 session 与 binding，验证 service_mode 与 control_epoch
 * 3. BOT 且 epoch 匹配时公开投递；HUMAN/QUEUED 或 epoch 变化时写入坐席便笺 (HELD_FOR_STAFF)
 * 4. 会话结束则标记 WAITING_SESSION，不强行复活会话
 */
@Slf4j
@Service
public class CustomerMoneyDeliveryService {

    private final CustomerDatabase db;
    private final AgentMoneyDeliveryMapper deliveryMapper;
    private final AgentMoneyStaffNoteMapper staffNoteMapper;
    private final SessionEventStore eventStore;
    private final ObjectMapper objectMapper;

    public CustomerMoneyDeliveryService(CustomerDatabase db,
                                        AgentMoneyDeliveryMapper deliveryMapper,
                                        AgentMoneyStaffNoteMapper staffNoteMapper,
                                        SessionEventStore eventStore,
                                        ObjectMapper objectMapper) {
        this.db = db;
        this.deliveryMapper = deliveryMapper;
        this.staffNoteMapper = staffNoteMapper;
        this.eventStore = eventStore;
        this.objectMapper = objectMapper;
    }

    public CustomerMoneyDeliveryService(CustomerDatabase db,
                                        AgentMoneyDeliveryMapper deliveryMapper,
                                        AgentMoneyStaffNoteMapper staffNoteMapper,
                                        SessionEventStore eventStore) {
        this(db, deliveryMapper, staffNoteMapper, eventStore, new ObjectMapper());
    }

    public boolean deliverProjectionUpdate(String caseNo, Long projectionRevision, Long recipientUserId,
                                           String sessionNo, String deliveryKind, String content,
                                           ObjectNode cardJson, Long expectedControlEpoch) {
        if (caseNo == null || projectionRevision == null || recipientUserId == null) {
            return false;
        }

        LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC);
        String payload = content != null ? content : "";
        String payloadHash = sha256(payload);

        // 1. 查询或创建唯一 delivery 记录
        AgentMoneyDeliveryEntity delivery = deliveryMapper.selectOne(new LambdaQueryWrapper<AgentMoneyDeliveryEntity>()
                .eq(AgentMoneyDeliveryEntity::getCaseNo, caseNo)
                .eq(AgentMoneyDeliveryEntity::getProjectionRevision, projectionRevision)
                .eq(AgentMoneyDeliveryEntity::getRecipientUserId, recipientUserId)
                .eq(AgentMoneyDeliveryEntity::getDeliveryKind, deliveryKind));

        if (delivery != null && "DELIVERED".equals(delivery.getState())) {
            return true; // 已经成功投递
        }

        if (delivery == null) {
            delivery = AgentMoneyDeliveryEntity.builder()
                    .deliveryNo(MoneyIdGenerator.generateDispatchNo().replace("MD", "ML"))
                    .caseNo(caseNo)
                    .projectionRevision(projectionRevision)
                    .recipientUserId(recipientUserId)
                    .deliveryKind(deliveryKind)
                    .sessionNo(sessionNo)
                    .expectedControlEpoch(expectedControlEpoch)
                    .state("PENDING")
                    .payloadHash(payloadHash)
                    .version(1L)
                    .createdAt(nowUtc)
                    .updatedAt(nowUtc)
                    .build();
            try {
                deliveryMapper.insert(delivery);
            } catch (Exception e) {
                // 并发插入冲突时查询已有
                delivery = deliveryMapper.selectOne(new LambdaQueryWrapper<AgentMoneyDeliveryEntity>()
                        .eq(AgentMoneyDeliveryEntity::getCaseNo, caseNo)
                        .eq(AgentMoneyDeliveryEntity::getProjectionRevision, projectionRevision)
                        .eq(AgentMoneyDeliveryEntity::getRecipientUserId, recipientUserId)
                        .eq(AgentMoneyDeliveryEntity::getDeliveryKind, deliveryKind));
            }
        }

        if (delivery == null || "DELIVERED".equals(delivery.getState())) {
            return true;
        }

        final AgentMoneyDeliveryEntity currentDelivery = delivery;

        return Boolean.TRUE.equals(db.tx.execute(status -> {
            if (sessionNo == null || sessionNo.isBlank()) {
                currentDelivery.setState("WAITING_SESSION");
                currentDelivery.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
                deliveryMapper.updateById(currentDelivery);
                return false;
            }

            // 2. 锁会话行
            var sessionRows = db.mysql.queryForList(
                    "SELECT session_no, user_id, status, service_mode, control_epoch, active_handoff_no FROM t_agent_session WHERE session_no = ? FOR UPDATE",
                    sessionNo
            );
            if (sessionRows.isEmpty()) {
                currentDelivery.setState("WAITING_SESSION");
                currentDelivery.setLastError("SESSION_NOT_FOUND");
                currentDelivery.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
                deliveryMapper.updateById(currentDelivery);
                return false;
            }

            Map<String, Object> s = sessionRows.getFirst();
            String sessionStatus = String.valueOf(s.get("status"));
            String serviceMode = s.get("service_mode") != null ? String.valueOf(s.get("service_mode")) : "BOT";
            long currentControlEpoch = s.get("control_epoch") != null ? ((Number) s.get("control_epoch")).longValue() : 1L;

            if ("CLOSED".equalsIgnoreCase(sessionStatus) || "EXPIRED".equalsIgnoreCase(sessionStatus)) {
                currentDelivery.setState("WAITING_SESSION");
                currentDelivery.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
                deliveryMapper.updateById(currentDelivery);
                return false;
            }

            boolean isBotMode = "BOT".equalsIgnoreCase(serviceMode);
            boolean epochMatches = (expectedControlEpoch == null || expectedControlEpoch.equals(currentControlEpoch));

            if (isBotMode && epochMatches) {
                // 3. BOT 且代际完全匹配：投递公开事件并落库
                String msgNo = "AM" + MoneyIdGenerator.generateDispatchNo().substring(2);
                db.mysql.update(
                        "INSERT INTO t_agent_message(message_no, session_no, role, content, citations_json) VALUES(?, ?, 'AGENT', ?, '[]')",
                        msgNo, sessionNo, content
                );
                db.mysql.update(
                        "UPDATE t_agent_session SET message_count = message_count + 1, last_active_time = now(3) WHERE session_no = ?",
                        sessionNo
                );

                if (eventStore != null) {
                    ObjectNode evt = object()
                            .put("messageNo", msgNo)
                            .put("sessionNo", sessionNo)
                            .put("role", "AGENT")
                            .put("content", content);
                    if (cardJson != null) {
                        evt.set("richCard", cardJson);
                    }
                    eventStore.appendPublicEventInTx(sessionNo, RealtimeProtocol.TYPE_MESSAGE_CREATED, evt);
                }

                currentDelivery.setState("DELIVERED");
                currentDelivery.setMessageNo(msgNo);
                currentDelivery.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
                deliveryMapper.updateById(currentDelivery);
                return true;
            } else {
                // 4. HUMAN / QUEUED 或 epoch 已改变：写入坐席便笺 t_agent_money_staff_note
                log.info("会话处于人工或代际变化状态 (mode={}, epoch={}, expected={})，写入坐席便笺, caseNo={}",
                        serviceMode, currentControlEpoch, expectedControlEpoch, caseNo);
                try {
                    String staffJson = "{\"caseNo\":\"" + caseNo + "\",\"content\":\"" + content + "\",\"projectionRevision\":" + projectionRevision + "}";
                    AgentMoneyStaffNoteEntity note = AgentMoneyStaffNoteEntity.builder()
                            .caseNo(caseNo)
                            .projectionRevision(projectionRevision)
                            .sessionNo(sessionNo)
                            .publicForStaffJson(staffJson)
                            .basedOnControlEpoch(currentControlEpoch)
                            .createdAt(LocalDateTime.now(ZoneOffset.UTC))
                            .build();
                    staffNoteMapper.insert(note);
                } catch (Exception ex) {
                    log.warn("写入坐席便笺异常: {}", ex.getMessage());
                }

                currentDelivery.setState("HELD_FOR_STAFF");
                currentDelivery.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
                deliveryMapper.updateById(currentDelivery);
                return false;
            }
        }));
    }

    private static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            return "hash_" + text.hashCode();
        }
    }
}
