package com.example.gateway.security;

import com.example.gateway.config.GatewaySecurityProperties;
import com.example.gateway.exception.GatewayForbiddenException;
import com.example.gateway.exception.GatewayUnauthorizedException;
import com.example.gateway.web.RequestMatcher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthenticationGlobalFilterTest {

    private final GatewaySecurityProperties properties = new GatewaySecurityProperties(
            JwtTestTokens.SECRET,
            List.of("GET:/api/public"),
            List.of("ANY:/api/admin/**")
    );
    private final AuthenticationGlobalFilter filter = new AuthenticationGlobalFilter(
            properties,
            new JwtVerifier(properties, new ObjectMapper()),
            new RequestMatcher()
    );
    private final GatewayFilterChain chain = exchange -> Mono.empty();

    @Test
    void allowsPublicPathWithoutToken() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/public").build()
        );

        filter.filter(exchange, chain).block();

        Object authenticatedUser = exchange.getAttribute(AuthenticationGlobalFilter.USER_ATTRIBUTE);
        assertThat(authenticatedUser).isNull();
    }

    @Test
    void rejectsProtectedPathWithoutToken() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/user/info").build()
        );

        assertThatThrownBy(() -> filter.filter(exchange, chain).block())
                .isInstanceOf(GatewayUnauthorizedException.class);
    }

    @Test
    void rejectsInvalidTokenEvenOnPublicPath() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/public")
                        .header("Authorization", "Bearer invalid-token")
                        .build()
        );

        assertThatThrownBy(() -> filter.filter(exchange, chain).block())
                .isInstanceOf(GatewayUnauthorizedException.class);
    }

    @Test
    void storesAuthenticatedUserForProtectedPath() {
        String token = JwtTestTokens.create(
                101L,
                "user@example.com",
                "USER",
                System.currentTimeMillis() + 60_000L
        );
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/user/info")
                        .header("Authorization", "Bearer " + token)
                        .build()
        );

        filter.filter(exchange, chain).block();

        Optional<AuthenticatedUser> user = Optional.ofNullable(
                exchange.getAttribute(AuthenticationGlobalFilter.USER_ATTRIBUTE)
        );
        assertThat(user).isPresent();
        assertThat(user.get().userId()).isEqualTo(101L);
    }

    @Test
    void rejectsNonAdminUserOnAdminPath() {
        String token = JwtTestTokens.create(
                101L,
                "user@example.com",
                "USER",
                System.currentTimeMillis() + 60_000L
        );
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/admin/risk/cases")
                        .header("Authorization", "Bearer " + token)
                        .build()
        );

        assertThatThrownBy(() -> filter.filter(exchange, chain).block())
                .isInstanceOf(GatewayForbiddenException.class);
    }

    @Test
    void allowsAdminUserOnAdminPath() {
        String token = JwtTestTokens.create(
                101L,
                "admin@example.com",
                "ADMIN",
                System.currentTimeMillis() + 60_000L
        );
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/admin/risk/cases")
                        .header("Authorization", "Bearer " + token)
                        .build()
        );

        filter.filter(exchange, chain).block();

        AuthenticatedUser user = exchange.getAttribute(AuthenticationGlobalFilter.USER_ATTRIBUTE);
        assertThat(user.isAdmin()).isTrue();
    }
}
