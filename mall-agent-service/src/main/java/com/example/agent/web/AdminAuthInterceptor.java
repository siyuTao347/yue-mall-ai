package com.example.agent.web;

import api.context.UserContext;
import api.response.ErrorCodes;
import com.example.agent.exception.RagApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 管理端身份校验：网关已按 admin-paths 拦一次，这里做服务内二次校验。
 * <p>优先使用网关注入的 {@code X-User-Id} / {@code X-User-Role}，未经过网关时回落 JWT 解析。</p>
 */
@Component
public class AdminAuthInterceptor implements HandlerInterceptor {

    private static final String ADMIN = "ADMIN";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        var actor=AgentActorResolver.resolve(request);
        if (!ADMIN.equalsIgnoreCase(actor.role())) {
            throw new RagApiException(403, ErrorCodes.COMMON_FORBIDDEN, "知识库管理接口仅限 ADMIN 访问");
        }
        request.setAttribute(AgentActorResolver.REQUEST_ATTRIBUTE,actor);
        UserContext.setUserId(actor.userId());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        UserContext.clear();
    }
}
