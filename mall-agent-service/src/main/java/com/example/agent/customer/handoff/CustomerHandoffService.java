package com.example.agent.customer.handoff;

import api.agent.AgentActor;
import com.example.agent.customer.CustomerDatabase;
import com.example.agent.customer.conversation.SessionEventStore;
import com.example.agent.customer.realtime.RealtimeProtocol;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

import static com.example.agent.customer.CustomerJson.*;

@Service
public class CustomerHandoffService {

    private static final Logger log = LoggerFactory.getLogger(CustomerHandoffService.class);

    private final CustomerDatabase db;
    private final SkillRoutingService routingService;
    private final SessionEventStore eventStore;

    public CustomerHandoffService(CustomerDatabase db, SkillRoutingService routingService, SessionEventStore eventStore) {
        this.db = db;
        this.routingService = routingService;
        this.eventStore = eventStore;
    }

    public record HandoffResult(String handoffNo, String sessionNo, String serviceMode, long controlEpoch, String targetGroup) {}

    public HandoffResult requestHandoff(AgentActor actor, String sessionNo, String reason, String riskLevel, String intent, String question, JsonNode context) {
        return db.tx.execute(tx -> {
            var sessionRows = db.mysql.queryForList(
                    "SELECT *, last_active_time < DATE_SUB(now(3), INTERVAL 24 HOUR) AS expired " +
                            "FROM t_agent_session WHERE session_no = ? FOR UPDATE",
                    sessionNo
            );
            if (sessionRows.isEmpty()) throw error(404, "AGENT_NOT_FOUND", "会话不存在");
            var s = sessionRows.getFirst();
            long sessionUserId = ((Number) s.get("user_id")).longValue();
            if (actor.userId() != sessionUserId && !"ADMIN".equalsIgnoreCase(actor.role())) {
                throw error(403, "AGENT_PERMISSION_DENIED", "无权转接他人会话");
            }

            // 检查是否已有活跃的转接单
            var existingHandoffs = db.mysql.queryForList(
                    "SELECT handoff_no, control_epoch, service_mode FROM t_agent_handoff h " +
                            "JOIN t_agent_session s ON s.session_no = h.session_no " +
                            "WHERE h.session_no = ? AND h.status IN ('OPEN', 'PROCESSING') LIMIT 1",
                    sessionNo
            );
            if (!existingHandoffs.isEmpty()) {
                var prev = existingHandoffs.getFirst();
                return new HandoffResult(
                        String.valueOf(prev.get("handoff_no")),
                        sessionNo,
                        String.valueOf(prev.get("service_mode")),
                        ((Number) prev.get("control_epoch")).longValue(),
                        "SENIOR_SUPPORT"
                );
            }

            // 路由决策
            var decision = routingService.route(riskLevel, intent, question);
            Long skillGroupId = routingService.getGroupIdByCode(decision.targetGroup());

            long nextEpoch = s.get("control_epoch") != null ? ((Number) s.get("control_epoch")).longValue() + 1L : 2L;
            String handoffNo = id("AH");

            // 插入转接记录表
            db.mysql.update(
                    "INSERT INTO t_agent_handoff(handoff_no, session_no, user_id, reason, priority, risk_level, " +
                            "skill_group_id, routing_rule_version, priority_rank, status, context_json, queued_time) " +
                            "VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, 'OPEN', ?, now(3))",
                    handoffNo, sessionNo, actor.userId(), reason != null ? reason : "用户请求人工客服",
                    "URGENT".equals(riskLevel) ? "URGENT" : "HIGH".equals(riskLevel) ? "HIGH" : "NORMAL",
                    riskLevel != null ? riskLevel : "NORMAL",
                    skillGroupId, decision.ruleCode() + ":v" + decision.ruleVersion(), decision.priorityRank(),
                    context != null ? write(context) : null
            );

            // 更新会话为 QUEUED 模式，递增代际，更新 active_handoff_no
            db.mysql.update(
                    "UPDATE t_agent_session SET service_mode = 'QUEUED', status = 'HANDOFF', control_epoch = ?, " +
                            "active_handoff_no = ?, last_active_time = now(3) WHERE session_no = ?",
                    nextEpoch, handoffNo, sessionNo
            );

            // 广播公开事件
            ObjectNode modeChangedPayload = object()
                    .put("serviceMode", "QUEUED")
                    .put("status", "HANDOFF")
                    .put("handoffNo", handoffNo)
                    .put("controlEpoch", nextEpoch)
                    .put("targetGroup", decision.targetGroup())
                    .put("notice", "已为你转接人工客服，正在排队接入中，你可以继续输入留言。");
            eventStore.appendPublicEventInTx(sessionNo, RealtimeProtocol.TYPE_SESSION_MODE_CHANGED, modeChangedPayload);

            return new HandoffResult(handoffNo, sessionNo, "QUEUED", nextEpoch, decision.targetGroup());
        });
    }

    public void claimHandoff(long staffUserId, String handoffNo, String operationId) {
        db.tx.executeWithoutResult(tx -> {
            // 1. 锁坐席容量行
            var staffRows = db.mysql.queryForList(
                    "SELECT staff_id, display_name, enabled, availability, max_capacity, used_capacity " +
                            "FROM t_agent_staff WHERE user_id = ? FOR UPDATE",
                    staffUserId
            );
            if (staffRows.isEmpty()) {
                // 若该账号尚未初始化坐席档案，自动注册一个坐席档案
                String staffId = "ST_" + staffUserId;
                db.mysql.update(
                        "INSERT INTO t_agent_staff(staff_id, user_id, display_name, staff_role, enabled, availability, max_capacity, used_capacity) " +
                                "VALUES(?, ?, ?, 'AGENT', 1, 'AVAILABLE', 5, 0)",
                        staffId, staffUserId, "客服工号" + staffUserId
                );
                staffRows = db.mysql.queryForList("SELECT * FROM t_agent_staff WHERE user_id = ? FOR UPDATE", staffUserId);
            }
            var staff = staffRows.getFirst();
            int maxCap = ((Number) staff.get("max_capacity")).intValue();
            int usedCap = ((Number) staff.get("used_capacity")).intValue();
            if (usedCap >= maxCap) {
                throw error(409, "STAFF_CAPACITY_EXCEEDED", "当前接待并发已达上限(" + maxCap + ")，无法认领新会话");
            }

            // 2. 锁转接记录行
            var handoffRows = db.mysql.queryForList(
                    "SELECT * FROM t_agent_handoff WHERE handoff_no = ? FOR UPDATE",
                    handoffNo
            );
            if (handoffRows.isEmpty()) throw error(404, "AGENT_NOT_FOUND", "转接单不存在");
            var h = handoffRows.getFirst();
            if (!"OPEN".equals(h.get("status"))) {
                throw error(409, "HANDOFF_ALREADY_CLAIMED", "该工单已被其他客服认领或已结束");
            }
            String sessionNo = String.valueOf(h.get("session_no"));

            // 3. 锁会话行
            var sessionRows = db.mysql.queryForList(
                    "SELECT control_epoch FROM t_agent_session WHERE session_no = ? FOR UPDATE",
                    sessionNo
            );
            long oldEpoch = sessionRows.isEmpty() ? 1L : ((Number) sessionRows.getFirst().get("control_epoch")).longValue();
            long newEpoch = oldEpoch + 1L;

            // 4. 更新转接单状态
            db.mysql.update(
                    "UPDATE t_agent_handoff SET status = 'PROCESSING', handler_id = ?, claimed_time = now(3), version = version + 1 " +
                            "WHERE handoff_no = ? AND status = 'OPEN'",
                    staffUserId, handoffNo
            );

            // 5. 更新会话状态为 HUMAN
            db.mysql.update(
                    "UPDATE t_agent_session SET service_mode = 'HUMAN', assigned_staff_id = ?, control_epoch = ?, " +
                            "last_active_time = now(3) WHERE session_no = ?",
                    staffUserId, newEpoch, sessionNo
            );

            // 6. 增加坐席已用容量
            db.mysql.update(
                    "UPDATE t_agent_staff SET used_capacity = used_capacity + 1, updated_time = now(3) WHERE user_id = ?",
                    staffUserId
            );

            // 7. 写审计流水
            String actionNo = id("ACT");
            db.mysql.update(
                    "INSERT INTO t_agent_handoff_audit(action_no, handoff_no, to_staff_id, old_epoch, new_epoch, action, operator_id, operation_id) " +
                            "VALUES(?, ?, ?, ?, ?, 'CLAIM', ?, ?)",
                    actionNo, handoffNo, String.valueOf(staff.get("staff_id")), oldEpoch, newEpoch, String.valueOf(staffUserId), operationId
            );

            // 8. 广播公开事件
            ObjectNode modeChangedPayload = object()
                    .put("serviceMode", "HUMAN")
                    .put("handoffNo", handoffNo)
                    .put("staffDisplayName", String.valueOf(staff.get("display_name")))
                    .put("controlEpoch", newEpoch)
                    .put("notice", "人工客服 " + staff.get("display_name") + " 已接入为您服务。");
            eventStore.appendPublicEventInTx(sessionNo, RealtimeProtocol.TYPE_SESSION_MODE_CHANGED, modeChangedPayload);
        });
    }

    public void transferHandoff(long currentStaffUserId, String handoffNo, Long targetStaffUserId, Long targetGroupId, String reason) {
        db.tx.executeWithoutResult(tx -> {
            var handoffRows = db.mysql.queryForList("SELECT * FROM t_agent_handoff WHERE handoff_no = ? FOR UPDATE", handoffNo);
            if (handoffRows.isEmpty()) throw error(404, "AGENT_NOT_FOUND", "转接单不存在");
            var h = handoffRows.getFirst();
            String sessionNo = String.valueOf(h.get("session_no"));

            var sessionRows = db.mysql.queryForList("SELECT control_epoch FROM t_agent_session WHERE session_no = ? FOR UPDATE", sessionNo);
            long oldEpoch = sessionRows.isEmpty() ? 1L : ((Number) sessionRows.getFirst().get("control_epoch")).longValue();
            long newEpoch = oldEpoch + 1L;

            // 释放原坐席容量
            db.mysql.update("UPDATE t_agent_staff SET used_capacity = GREATEST(0, used_capacity - 1) WHERE user_id = ?", currentStaffUserId);

            if (targetStaffUserId != null) {
                // 认领目标坐席容量
                var targetStaff = db.mysql.queryForList("SELECT * FROM t_agent_staff WHERE user_id = ? FOR UPDATE", targetStaffUserId);
                if (targetStaff.isEmpty() || ((Number) targetStaff.getFirst().get("used_capacity")).intValue() >= ((Number) targetStaff.getFirst().get("max_capacity")).intValue()) {
                    throw error(409, "STAFF_CAPACITY_EXCEEDED", "目标坐席容量不足");
                }
                db.mysql.update("UPDATE t_agent_staff SET used_capacity = used_capacity + 1 WHERE user_id = ?", targetStaffUserId);
                db.mysql.update("UPDATE t_agent_handoff SET handler_id = ?, version = version + 1 WHERE handoff_no = ?", targetStaffUserId, handoffNo);
                db.mysql.update("UPDATE t_agent_session SET assigned_staff_id = ?, control_epoch = ? WHERE session_no = ?", targetStaffUserId, newEpoch, sessionNo);
            } else {
                // 转回排队队列
                db.mysql.update("UPDATE t_agent_handoff SET status = 'OPEN', handler_id = NULL, skill_group_id = ?, version = version + 1 WHERE handoff_no = ?", targetGroupId, handoffNo);
                db.mysql.update("UPDATE t_agent_session SET service_mode = 'QUEUED', assigned_staff_id = NULL, control_epoch = ? WHERE session_no = ?", newEpoch, sessionNo);
            }

            String actionNo = id("ACT");
            db.mysql.update(
                    "INSERT INTO t_agent_handoff_audit(action_no, handoff_no, from_staff_id, to_staff_id, old_epoch, new_epoch, action, reason, operator_id) " +
                            "VALUES(?, ?, ?, ?, ?, 'TRANSFER', ?, ?)",
                    actionNo, handoffNo, String.valueOf(currentStaffUserId), String.valueOf(targetStaffUserId), oldEpoch, newEpoch, reason, String.valueOf(currentStaffUserId)
            );

            ObjectNode modePayload = object().put("serviceMode", targetStaffUserId != null ? "HUMAN" : "QUEUED").put("controlEpoch", newEpoch).put("notice", "会话已转派。");
            eventStore.appendPublicEventInTx(sessionNo, RealtimeProtocol.TYPE_SESSION_MODE_CHANGED, modePayload);
        });
    }

    public void resolveHandoff(long staffUserId, String handoffNo, String disposition, String resolution) {
        db.tx.executeWithoutResult(tx -> {
            var handoffRows = db.mysql.queryForList("SELECT * FROM t_agent_handoff WHERE handoff_no = ? FOR UPDATE", handoffNo);
            if (handoffRows.isEmpty()) throw error(404, "AGENT_NOT_FOUND", "转接单不存在");
            var h = handoffRows.getFirst();
            String sessionNo = String.valueOf(h.get("session_no"));

            var sessionRows = db.mysql.queryForList("SELECT control_epoch FROM t_agent_session WHERE session_no = ? FOR UPDATE", sessionNo);
            long oldEpoch = sessionRows.isEmpty() ? 1L : ((Number) sessionRows.getFirst().get("control_epoch")).longValue();
            long newEpoch = oldEpoch + 1L;

            // 1. 更新转接单
            String effDisposition = disposition != null ? disposition : "CLOSE";
            db.mysql.update(
                    "UPDATE t_agent_handoff SET status = 'RESOLVED', resolved_time = now(3), resolution = ?, " +
                            "end_disposition = ?, version = version + 1 WHERE handoff_no = ?",
                    redact(resolution != null ? resolution : "已处理完成"), effDisposition, handoffNo
            );

            // 2. 释放坐席容量
            db.mysql.update("UPDATE t_agent_staff SET used_capacity = GREATEST(0, used_capacity - 1) WHERE user_id = ?", staffUserId);

            // 3. 更新会话模式
            if ("RETURN_TO_BOT".equalsIgnoreCase(effDisposition)) {
                db.mysql.update(
                        "UPDATE t_agent_session SET service_mode = 'BOT', status = 'ACTIVE', active_handoff_no = NULL, " +
                                "assigned_staff_id = NULL, control_epoch = ?, last_active_time = now(3) WHERE session_no = ?",
                        newEpoch, sessionNo
                );
            } else {
                db.mysql.update(
                        "UPDATE t_agent_session SET service_mode = 'NONE', status = 'CLOSED', active_handoff_no = NULL, " +
                                "assigned_staff_id = NULL, control_epoch = ?, closed_time = now(3) WHERE session_no = ?",
                        newEpoch, sessionNo
                );
            }

            // 4. 写审计
            String actionNo = id("ACT");
            db.mysql.update(
                    "INSERT INTO t_agent_handoff_audit(action_no, handoff_no, from_staff_id, old_epoch, new_epoch, action, reason, operator_id) " +
                            "VALUES(?, ?, ?, ?, ?, 'RESOLVE', ?, ?)",
                    actionNo, handoffNo, String.valueOf(staffUserId), oldEpoch, newEpoch, resolution, String.valueOf(staffUserId)
            );

            // 5. 广播模式切换事件
            ObjectNode modePayload = object()
                    .put("serviceMode", "RETURN_TO_BOT".equalsIgnoreCase(effDisposition) ? "BOT" : "NONE")
                    .put("status", "RETURN_TO_BOT".equalsIgnoreCase(effDisposition) ? "ACTIVE" : "CLOSED")
                    .put("controlEpoch", newEpoch)
                    .put("notice", "RETURN_TO_BOT".equalsIgnoreCase(effDisposition) ? "人工服务已结束，已为你切回智能客服。" : "本次服务已结束，感谢你的咨询。");
            eventStore.appendPublicEventInTx(sessionNo, RealtimeProtocol.TYPE_SESSION_MODE_CHANGED, modePayload);
        });
    }

    public List<Map<String, Object>> listQueue(String status, Long skillGroupId) {
        String effStatus = status != null ? status : "OPEN";
        if (skillGroupId != null && skillGroupId > 0) {
            return db.mysql.queryForList(
                    "SELECT handoff_no, session_no, user_id, reason, priority, risk_level, skill_group_id, " +
                            "priority_rank, status, handler_id, queued_time, created_time FROM t_agent_handoff " +
                            "WHERE status = ? AND skill_group_id = ? ORDER BY priority_rank ASC, queued_time ASC LIMIT 50",
                    effStatus, skillGroupId
            );
        }
        return db.mysql.queryForList(
                "SELECT handoff_no, session_no, user_id, reason, priority, risk_level, skill_group_id, " +
                        "priority_rank, status, handler_id, queued_time, created_time FROM t_agent_handoff " +
                        "WHERE status = ? ORDER BY priority_rank ASC, queued_time ASC LIMIT 50",
                effStatus
        );
    }
}
