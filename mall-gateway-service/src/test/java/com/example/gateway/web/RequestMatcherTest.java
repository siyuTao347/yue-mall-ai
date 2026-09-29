package com.example.gateway.web;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RequestMatcherTest {

    private final RequestMatcher requestMatcher = new RequestMatcher();

    @Test
    void matchesMethodAndPath() {
        assertThat(requestMatcher.matches(
                List.of("GET:/api/item/**"),
                MockServerHttpRequest.get("/api/item/1").build()
        )).isTrue();

        assertThat(requestMatcher.matches(
                List.of("GET:/api/item/**"),
                MockServerHttpRequest.post("/api/item/1").build()
        )).isFalse();
    }

    @Test
    void matchesAnyMethodWhenConfigured() {
        assertThat(requestMatcher.matches(
                List.of("ANY:/api/admin/**"),
                MockServerHttpRequest.post("/api/admin/risk/cases").build()
        )).isTrue();
    }

    @Test
    void matchesPathPatternForRateLimitRules() {
        assertThat(requestMatcher.matches("/api/seckill/*/doSeckill", "/api/seckill/abc123/doSeckill"))
                .isTrue();
        assertThat(requestMatcher.matches("/api/seckill/*/doSeckill", "/api/seckill/getPath"))
                .isFalse();
    }
}
