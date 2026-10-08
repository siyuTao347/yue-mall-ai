package com.example.user.web;

import api.context.TraceContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 全链路 traceId 贯通：网关透传 X-Trace-Id / traceparent / X-Request-Id，业务服务兜底生成。
 * <p>仅接受合法 32 位十六进制 trace-id 或 UUID，避免把任意输入写入日志与响应头。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceContextFilter extends OncePerRequestFilter {

    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String TRACEPARENT_HEADER = "traceparent";
    private static final String MDC_KEY = "traceId";
    private static final Pattern HEX_32 = Pattern.compile("^[0-9a-fA-F]{32}$");
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String traceId = extractTraceId(request);
        try {
            TraceContext.set(traceId);
            MDC.put(MDC_KEY, traceId);
            response.setHeader(REQUEST_ID_HEADER, traceId);
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
            TraceContext.clear();
        }
    }

    private String extractTraceId(HttpServletRequest request) {
        String traceId = normalizeHex32(request.getHeader(TRACE_ID_HEADER));
        if (traceId != null) {
            return traceId;
        }
        traceId = fromTraceparent(request.getHeader(TRACEPARENT_HEADER));
        if (traceId != null) {
            return traceId;
        }
        String requestId = request.getHeader(REQUEST_ID_HEADER);
        if (requestId != null) {
            String trimmed = requestId.trim();
            if (UUID_PATTERN.matcher(trimmed).matches()) {
                return trimmed;
            }
        }
        return UUID.randomUUID().toString();
    }

    private String normalizeHex32(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return HEX_32.matcher(trimmed).matches() ? trimmed.toLowerCase() : null;
    }

    private String fromTraceparent(String header) {
        if (header == null) {
            return null;
        }
        String[] parts = header.trim().split("-");
        if (parts.length != 4) {
            return null;
        }
        return normalizeHex32(parts[1]);
    }
}
