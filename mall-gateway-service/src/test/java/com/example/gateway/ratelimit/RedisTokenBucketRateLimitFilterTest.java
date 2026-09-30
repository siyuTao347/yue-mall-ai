package com.example.gateway.ratelimit;

import com.example.gateway.config.GatewayNetworkProperties;
import com.example.gateway.config.GatewayRateLimitProperties;
import com.example.gateway.exception.GatewayRateLimitedException;
import com.example.gateway.web.ClientIpResolver;
import com.example.gateway.web.RequestMatcher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedisTokenBucketRateLimitFilterTest {

    private ReactiveStringRedisTemplate redisTemplate;
    private RedisTokenBucketRateLimitFilter filter;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(ReactiveStringRedisTemplate.class);
        filter = new RedisTokenBucketRateLimitFilter(
                new GatewayRateLimitProperties(true, List.of(paymentCallbackRule())),
                redisTemplate,
                new RequestMatcher(),
                new RateLimitKeyFactory(
                        new ClientIpResolver(new GatewayNetworkProperties(List.of("127.0.0.1")))),
                new ObjectMapper());
    }

    @Test
    void readsPaymentNoAndForwardsRequestBody() {
        allowRequest();
        AtomicReference<ServerWebExchange> downstreamExchange = new AtomicReference<>();
        GatewayFilterChain chain = exchange -> {
            downstreamExchange.set(exchange);
            return Mono.empty();
        };
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/payment/callback")
                        .body("{\"paymentNo\":\"PAY1\",\"result\":\"SUCCESS\"}"));

        filter.filter(exchange, chain).block();

        Assertions.assertEquals("PAY1", downstreamExchange.get()
                .getAttribute(RateLimitKeyFactory.PAYMENT_NO_ATTRIBUTE));
        Assertions.assertEquals("{\"paymentNo\":\"PAY1\",\"result\":\"SUCCESS\"}",
                readBody(downstreamExchange.get()));
    }

    @Test
    void rejectsRequestWhenTokenBucketIsEmpty() {
        when(redisTemplate.execute(
                ArgumentMatchers.<RedisScript<Long>>any(), anyList(), anyList()))
                .thenReturn(Flux.just(0L));
        MockServerWebExchange callbackExchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/payment/callback")
                        .body("{\"paymentNo\":\"PAY1\"}"));

        Assertions.assertThrows(GatewayRateLimitedException.class,
                () -> filter.filter(callbackExchange, ignoredExchange -> Mono.empty()).block());
    }

    private void allowRequest() {
        when(redisTemplate.execute(
                ArgumentMatchers.<RedisScript<Long>>any(), anyList(), anyList()))
                .thenReturn(Flux.just(1L));
    }

    private String readBody(ServerWebExchange exchange) {
        return DataBufferUtils.join(exchange.getRequest().getBody())
                .map(this::toStringValue)
                .block();
    }

    private String toStringValue(DataBuffer dataBuffer) {
        try {
            byte[] bytes = new byte[dataBuffer.readableByteCount()];
            dataBuffer.read(bytes);
            return new String(bytes, StandardCharsets.UTF_8);
        } finally {
            DataBufferUtils.release(dataBuffer);
        }
    }

    private GatewayRateLimitProperties.RateLimitRule paymentCallbackRule() {
        return new GatewayRateLimitProperties.RateLimitRule(
                "payment-callback",
                "/api/payment/callback",
                GatewayRateLimitProperties.KeyType.PAYMENT_CALLBACK,
                1,
                10
        );
    }
}
