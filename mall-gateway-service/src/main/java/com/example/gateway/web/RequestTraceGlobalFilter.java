package com.example.gateway.web;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;
import java.util.regex.Pattern;

@Component
public class RequestTraceGlobalFilter implements GlobalFilter, Ordered {

    public static final String REQUEST_ID_ATTRIBUTE = "gateway.requestId";

    private static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final String TRACE_PARENT_HEADER = "traceparent";
    private static final Pattern REQUEST_ID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
    );
    private static final Pattern TRACE_PARENT_PATTERN = Pattern.compile(
            "^00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$"
    );

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String requestId = sanitizeRequestId(request.getHeaders().getFirst(REQUEST_ID_HEADER));
        String traceParent = sanitizeTraceParent(request.getHeaders().getFirst(TRACE_PARENT_HEADER));

        ServerHttpRequest mutatedRequest = request.mutate()
                .headers(headers -> {
                    headers.set(REQUEST_ID_HEADER, requestId);
                    headers.set(TRACE_PARENT_HEADER, traceParent);
                })
                .build();

        exchange.getAttributes().put(REQUEST_ID_ATTRIBUTE, requestId);
        exchange.getResponse().getHeaders().set(REQUEST_ID_HEADER, requestId);
        return chain.filter(exchange.mutate().request(mutatedRequest).build());
    }

    @Override
    public int getOrder() {
        return -100;
    }

    private String sanitizeRequestId(String value) {
        return StringUtils.hasText(value) && REQUEST_ID_PATTERN.matcher(value).matches()
                ? value
                : UUID.randomUUID().toString();
    }

    private String sanitizeTraceParent(String value) {
        return StringUtils.hasText(value) && TRACE_PARENT_PATTERN.matcher(value).matches()
                ? value
                : generateTraceParent();
    }

    private String generateTraceParent() {
        String randomId = UUID.randomUUID().toString().replace("-", "");
        return "00-" + randomId + "-" + randomId.substring(0, 16) + "-01";
    }
}
