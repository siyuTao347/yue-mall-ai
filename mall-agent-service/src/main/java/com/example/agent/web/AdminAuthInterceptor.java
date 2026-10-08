package com.example.agent.web;

import api.context.UserContext;
import api.response.ErrorCodes;
import api.util.JwtUtil;
import com.example.agent.exception.RagApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 管理端身份校验：网关已按 admin-paths 拦一次，这里做服务内二次校验。
 * <p>优先使用网关注入的 {@code X-User-Id} / {@code X-User-Role}，未经过网关时回落 JWT 解析。</p>
 */
@Component
public class AdminAuthInterceptor implements HandlerInterceptor {

    private static final String USER_ID_HEADER = "X-User-Id";
    private static final String USER_ROLE_HEADER = "X-User-Role";
    private static final String ADMIN = "ADMIN";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String authorization = request.getHeader("Authorization");
        Long userId = parseUserId(request.getHeader(USER_ID_HEADER));
        if (userId == null) {
            userId = JwtUtil.parseUserId(authorization);
        }
        String role = request.getHeader(USER_ROLE_HEADER);
        if (!StringUtils.hasText(role)) {
            role = JwtUtil.parseRole(authorization);
        }
        if (!ADMIN.equalsIgnoreCase(role == null ? "" : role.trim())) {
            throw new RagApiException(403, ErrorCodes.COMMON_FORBIDDEN, "知识库管理接口仅限 ADMIN 访问");
        }
        if (userId != null) {
            UserContext.setUserId(userId);
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        UserContext.clear();
    }

    private Long parseUserId(String header) {
        if (!StringUtils.hasText(header)) {
            return null;
        }
        try {
            return Long.valueOf(header.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
