package com.example.agent.web;

import api.agent.AgentActor;
import api.util.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.util.StringUtils;
import com.example.agent.exception.RagApiException;

/** One identity policy for agent controllers, argument binding and the admin interceptor. */
public final class AgentActorResolver {
    public static final String REQUEST_ATTRIBUTE=AgentActorResolver.class.getName()+".actor";

    private AgentActorResolver() {}

    public static AgentActor resolve(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        Long jwtId = JwtUtil.parseUserId(authorization);
        if (jwtId == null || jwtId <= 0) {
            throw new RagApiException(401, "AGENT_UNAUTHENTICATED", "请先登录");
        }

        String headerUserIdStr = request.getHeader("X-User-Id");
        Long headerId = parseId(headerUserIdStr);
        if (StringUtils.hasText(headerUserIdStr) && headerId == null) {
            throw new RagApiException(403, "AGENT_PERMISSION_DENIED", "网关身份格式不正确");
        }
        if (headerId != null && !jwtId.equals(headerId)) {
            throw new RagApiException(403, "AGENT_PERMISSION_DENIED", "身份不一致");
        }

        String jwtRole = normalizeRole(JwtUtil.parseRole(authorization));
        String headerRole = normalizeRole(request.getHeader("X-User-Role"));
        if (jwtRole != null && headerRole != null && !jwtRole.equals(headerRole)) {
            throw new RagApiException(403, "AGENT_PERMISSION_DENIED", "身份角色不一致");
        }
        String effectiveRole = jwtRole != null ? jwtRole : (headerRole != null ? headerRole : "USER");
        return new AgentActor(jwtId, effectiveRole);
    }

    private static Long parseId(String value) {
        if(!StringUtils.hasText(value))return null;
        try {return Long.valueOf(value.trim());}catch(NumberFormatException ignored){return null;}
    }

    private static String normalizeRole(String role) {
        if(!StringUtils.hasText(role))return null;
        String normalized=role.trim().toUpperCase(java.util.Locale.ROOT);
        return normalized.startsWith("ROLE_")?normalized.substring(5):normalized;
    }
}
