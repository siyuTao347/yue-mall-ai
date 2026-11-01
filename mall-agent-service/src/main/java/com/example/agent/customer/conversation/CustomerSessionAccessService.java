package com.example.agent.customer.conversation;

import api.agent.AgentActor;
import com.example.agent.customer.CustomerDatabase;
import org.springframework.stereotype.Service;

import java.util.Map;

import static com.example.agent.customer.CustomerJson.error;

/**
 * 统一会话访问控制服务
 * 严格校验 actor 对 session 的归属与读写权限，禁止未授权跨会话访问
 */
@Service
public class CustomerSessionAccessService {

    private final CustomerDatabase db;

    public CustomerSessionAccessService(CustomerDatabase db) {
        this.db = db;
    }

    public record SessionInfo(String sessionNo, Long userId, String status, String serviceMode, Long controlEpoch) {}

    /**
     * 校验并获取会话信息
     */
    public SessionInfo validateAndGetSession(AgentActor actor, String sessionNo) {
        if (sessionNo == null || sessionNo.isBlank()) {
            throw error(400, "AGENT_PARAM_INVALID", "会话编号不能为空");
        }
        if (actor == null || actor.userId() <= 0) {
            throw error(401, "AGENT_UNAUTHENTICATED", "未登录或身份缺失");
        }

        var rows = db.mysql.queryForList(
                "SELECT session_no, user_id, status, service_mode, control_epoch FROM t_agent_session WHERE session_no = ?",
                sessionNo
        );
        if (rows.isEmpty()) {
            throw error(404, "AGENT_NOT_FOUND", "会话不存在或无权访问");
        }

        Map<String, Object> s = rows.getFirst();
        long sessionUserId = ((Number) s.get("user_id")).longValue();
        String status = String.valueOf(s.get("status"));
        String serviceMode = s.get("service_mode") != null ? String.valueOf(s.get("service_mode")) : "BOT";
        long controlEpoch = s.get("control_epoch") != null ? ((Number) s.get("control_epoch")).longValue() : 1L;

        boolean isStaff = "STAFF".equalsIgnoreCase(actor.role()) || "ADMIN".equalsIgnoreCase(actor.role());
        if (!isStaff && actor.userId() != sessionUserId) {
            throw error(403, "AGENT_PERMISSION_DENIED", "无法访问他人会话");
        }

        return new SessionInfo(sessionNo, sessionUserId, status, serviceMode, controlEpoch);
    }

    /**
     * 校验会话是否处于活跃状态（非 CLOSED/EXPIRED）
     */
    public SessionInfo validateActiveSession(AgentActor actor, String sessionNo) {
        SessionInfo info = validateAndGetSession(actor, sessionNo);
        if ("CLOSED".equalsIgnoreCase(info.status()) || "EXPIRED".equalsIgnoreCase(info.status())) {
            throw error(409, "AGENT_SESSION_INACTIVE", "会话已关闭或已失效");
        }
        return info;
    }
}
