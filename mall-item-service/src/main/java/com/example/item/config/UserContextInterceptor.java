package com.example.item.config;

import api.context.UserContext;
import api.util.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class UserContextInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 1. 优先从 Authorization Header 提取 JWT
        String authHeader = request.getHeader("Authorization");
        Long userId = JwtUtil.parseUserId(authHeader);

        // 2. 若 Header 未带 Token，兼容 Query 参数中的 userId (供压测脚本回退兼容)
        if (userId == null) {
            String paramUserId = request.getParameter("userId");
            if (paramUserId != null && !paramUserId.isEmpty()) {
                try {
                    userId = Long.valueOf(paramUserId);
                } catch (NumberFormatException ignored) {}
            }
        }

        if (userId != null) {
            UserContext.setUserId(userId);
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        UserContext.clear();
    }
}
