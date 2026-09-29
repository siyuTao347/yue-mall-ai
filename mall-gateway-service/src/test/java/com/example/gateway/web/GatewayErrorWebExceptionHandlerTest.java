package com.example.gateway.web;

import com.example.gateway.exception.GatewayRateLimitedException;
import com.example.gateway.exception.GatewayUnauthorizedException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayErrorWebExceptionHandlerTest {

    private final GatewayErrorWebExceptionHandler handler = new GatewayErrorWebExceptionHandler(
            new ObjectMapper()
    );

    @Test
    void writesUnauthorizedJsonResponse() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/user/info").build()
        );

        handler.handle(exchange, new GatewayUnauthorizedException()).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(exchange.getResponse().getHeaders().getContentType())
                .isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(exchange.getResponse().getBodyAsString().block())
                .contains("\"code\":401")
                .contains("登录已失效");
    }

    @Test
    void writesRateLimitedJsonResponse() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/seckill/getPath").build()
        );

        handler.handle(exchange, new GatewayRateLimitedException()).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(exchange.getResponse().getBodyAsString().block())
                .contains("\"code\":429")
                .contains("请求过于频繁");
    }
}
