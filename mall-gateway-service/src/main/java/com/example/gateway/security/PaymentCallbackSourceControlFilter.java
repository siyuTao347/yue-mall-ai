package com.example.gateway.security;

import com.example.gateway.config.GatewayPaymentCallbackProperties;
import com.example.gateway.exception.GatewayForbiddenException;
import com.example.gateway.web.ClientIpResolver;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class PaymentCallbackSourceControlFilter implements GlobalFilter, Ordered {

    private static final String CALLBACK_PATH = "/api/payment/callback";

    private final GatewayPaymentCallbackProperties properties;
    private final ClientIpResolver clientIpResolver;

    public PaymentCallbackSourceControlFilter(
            GatewayPaymentCallbackProperties properties,
            ClientIpResolver clientIpResolver
    ) {
        this.properties = properties;
        this.clientIpResolver = clientIpResolver;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        if (!properties.sourceControlEnabled()
                || !HttpMethod.POST.equals(request.getMethod())
                || !CALLBACK_PATH.equals(request.getPath().value())) {
            return chain.filter(exchange);
        }

        String sourceIp = clientIpResolver.resolve(request);
        if (properties.allowedSourceIps().contains(sourceIp)) {
            return chain.filter(exchange);
        }
        return Mono.error(new GatewayForbiddenException());
    }

    @Override
    public int getOrder() {
        return -80;
    }
}
