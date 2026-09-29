package com.example.gateway.web;

import com.example.gateway.security.AuthenticationGlobalFilter;
import com.example.gateway.security.AuthenticatedUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class GatewayAccessLogFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(GatewayAccessLogFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        long startTime = System.nanoTime();
        ServerHttpRequest request = exchange.getRequest();

        return chain.filter(exchange).doFinally(signal -> {
            long costMs = (System.nanoTime() - startTime) / 1_000_000;
            String requestId = exchange.getAttribute(RequestTraceGlobalFilter.REQUEST_ID_ATTRIBUTE);
            AuthenticatedUser user = exchange.getAttribute(AuthenticationGlobalFilter.USER_ATTRIBUTE);
            var status = exchange.getResponse().getStatusCode();
            log.info(
                    "gateway access: requestId={}, userId={}, method={}, path={}, status={}, costMs={}",
                    requestId,
                    user == null ? "-" : user.userId(),
                    request.getMethod(),
                    request.getPath().value(),
                    status,
                    costMs
            );
        });
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
