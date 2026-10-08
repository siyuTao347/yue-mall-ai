package com.example.item.web;

import api.response.ApiResponse;
import api.response.ErrorCodes;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * {@code /api/internal/**} 服务内默认拒绝。
 *
 * <p>仅当 {@code mall.internal-api.enabled=true} 时该 Bean 不注册；与网关无路由、
 * 演示 Bean 的 {@code @Profile} 一起构成内部/演示接口的多重防线，避免服务直连或
 * 网关配置遗漏时被外部访问。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@ConditionalOnProperty(prefix = "mall.internal-api", name = "enabled",
        havingValue = "false", matchIfMissing = true)
public class InternalAccessFilter extends OncePerRequestFilter {

    private static final String INTERNAL_PREFIX = "/api/internal/";

    private final ObjectMapper objectMapper;

    public InternalAccessFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();
        if (path != null && path.startsWith(INTERNAL_PREFIX)) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write(objectMapper.writeValueAsString(
                    ApiResponse.error(HttpServletResponse.SC_NOT_FOUND,
                            ErrorCodes.COMMON_NOT_FOUND, "资源不存在")));
            return;
        }
        chain.doFilter(request, response);
    }
}
