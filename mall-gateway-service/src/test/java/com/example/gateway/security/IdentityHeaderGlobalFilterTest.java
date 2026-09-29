package com.example.gateway.security;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class IdentityHeaderGlobalFilterTest {

    private final IdentityHeaderGlobalFilter filter = new IdentityHeaderGlobalFilter();

    @Test
    void overwritesUntrustedIdentityHeaders() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/user/info")
                        .header("X-User-Id", "999")
                        .header("X-User-Email", "attacker@example.com")
                        .header("X-User-Role", "ADMIN")
                        .build()
        );
        exchange.getAttributes().put(
                AuthenticationGlobalFilter.USER_ATTRIBUTE,
                new AuthenticatedUser(101L, "user@example.com", "USER")
        );
        AtomicReference<ServerWebExchange> downstreamExchange = new AtomicReference<>();
        GatewayFilterChain chain = currentExchange -> {
            downstreamExchange.set(currentExchange);
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();

        var headers = downstreamExchange.get().getRequest().getHeaders();
        assertThat(headers.getFirst("X-User-Id")).isEqualTo("101");
        assertThat(headers.getFirst("X-User-Email")).isEqualTo("user@example.com");
        assertThat(headers.getFirst("X-User-Role")).isEqualTo("USER");
    }

    @Test
    void removesIdentityHeadersForAnonymousRequest() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/public")
                        .header("X-User-Id", "999")
                        .header("X-User-Role", "ADMIN")
                        .build()
        );
        AtomicReference<ServerWebExchange> downstreamExchange = new AtomicReference<>();
        GatewayFilterChain chain = currentExchange -> {
            downstreamExchange.set(currentExchange);
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();

        var headers = downstreamExchange.get().getRequest().getHeaders();
        assertThat(headers.getFirst("X-User-Id")).isNull();
        assertThat(headers.getFirst("X-User-Email")).isNull();
        assertThat(headers.getFirst("X-User-Role")).isNull();
    }
}
