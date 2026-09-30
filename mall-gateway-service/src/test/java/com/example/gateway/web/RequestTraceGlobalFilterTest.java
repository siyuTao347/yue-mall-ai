package com.example.gateway.web;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import java.util.concurrent.atomic.AtomicReference;

class RequestTraceGlobalFilterTest {

    private final RequestTraceGlobalFilter filter = new RequestTraceGlobalFilter();

    @Test
    void preservesValidTraceIdAndSetsTraceHeaders() {
        AtomicReference<ServerWebExchange> downstreamExchange = new AtomicReference<>();
        GatewayFilterChain chain = exchange -> {
            downstreamExchange.set(exchange);
            return Mono.empty();
        };
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/trade/orders")
                        .header("X-Request-Id", "01234567-89ab-cdef-0123-456789abcdef")
                        .header("traceparent", "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01")
                        .header("X-Trace-Id", "0123456789abcdef0123456789abcdef")
                        .build());

        filter.filter(exchange, chain).block();

        Assertions.assertEquals("0123456789abcdef0123456789abcdef",
                downstreamExchange.get().getRequest().getHeaders().getFirst("X-Trace-Id"));
        Assertions.assertEquals("01234567-89ab-cdef-0123-456789abcdef",
                exchange.getResponse().getHeaders().getFirst("X-Request-Id"));
    }

    @Test
    void generatesTraceIdFromSanitizedTraceParent() {
        AtomicReference<ServerWebExchange> downstreamExchange = new AtomicReference<>();
        GatewayFilterChain chain = exchange -> {
            downstreamExchange.set(exchange);
            return Mono.empty();
        };
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/trade/orders")
                        .header("traceparent", "invalid-trace-parent")
                        .build());

        filter.filter(exchange, chain).block();

        String traceParent = downstreamExchange.get().getRequest().getHeaders().getFirst("traceparent");
        String traceId = downstreamExchange.get().getRequest().getHeaders().getFirst("X-Trace-Id");
        Assertions.assertTrue(traceParent.startsWith("00-"));
        Assertions.assertEquals(32, traceId.length());
        Assertions.assertEquals(traceParent.split("-")[1], traceId);
    }
}
