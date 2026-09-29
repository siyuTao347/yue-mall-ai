package com.example.gateway.security;

import com.example.gateway.config.GatewaySecurityProperties;
import com.example.gateway.exception.GatewayForbiddenException;
import com.example.gateway.exception.GatewayUnauthorizedException;
import com.example.gateway.web.RequestMatcher;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Optional;

@Component
public class AuthenticationGlobalFilter implements GlobalFilter, Ordered {

    public static final String USER_ATTRIBUTE = "gateway.authenticatedUser";

    private final GatewaySecurityProperties properties;
    private final JwtVerifier jwtVerifier;
    private final RequestMatcher requestMatcher;

    public AuthenticationGlobalFilter(
            GatewaySecurityProperties properties,
            JwtVerifier jwtVerifier,
            RequestMatcher requestMatcher
    ) {
        this.properties = properties;
        this.jwtVerifier = jwtVerifier;
        this.requestMatcher = requestMatcher;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return Mono.defer(() -> {
            ServerHttpRequest request = exchange.getRequest();
            if (HttpMethod.OPTIONS.equals(request.getMethod())) {
                return chain.filter(exchange);
            }

            String authorizationHeader = request.getHeaders().getFirst("Authorization");
            Optional<AuthenticatedUser> authenticatedUser = jwtVerifier.verify(authorizationHeader);
            authenticatedUser.ifPresent(user -> exchange.getAttributes().put(USER_ATTRIBUTE, user));

            if (StringUtils.hasText(authorizationHeader) && authenticatedUser.isEmpty()) {
                throw new GatewayUnauthorizedException();
            }
            if (authenticatedUser.isEmpty()
                    && !requestMatcher.matches(properties.publicPaths(), request)) {
                throw new GatewayUnauthorizedException();
            }
            if (requestMatcher.matches(properties.adminPaths(), request)
                    && authenticatedUser.map(user -> !user.isAdmin()).orElse(true)) {
                throw new GatewayForbiddenException();
            }

            return chain.filter(exchange);
        });
    }

    @Override
    public int getOrder() {
        return -90;
    }
}
