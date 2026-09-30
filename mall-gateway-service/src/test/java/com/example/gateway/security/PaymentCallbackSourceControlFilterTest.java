package com.example.gateway.security;

import com.example.gateway.config.GatewayNetworkProperties;
import com.example.gateway.config.GatewayPaymentCallbackProperties;
import com.example.gateway.exception.GatewayForbiddenException;
import com.example.gateway.web.ClientIpResolver;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentCallbackSourceControlFilterTest {

    private GatewayFilterChain chain;
    private PaymentCallbackSourceControlFilter filter;

    @BeforeEach
    void setUp() {
        chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());
        filter = new PaymentCallbackSourceControlFilter(
                new GatewayPaymentCallbackProperties(true, List.of("203.0.113.10")),
                new ClientIpResolver(new GatewayNetworkProperties(List.of("127.0.0.1"))));
    }

    @Test
    void allowsConfiguredCallbackSource() {
        MockServerWebExchange exchange = callbackExchange("203.0.113.10");

        filter.filter(exchange, chain).block();

        verify(chain).filter(exchange);
    }

    @Test
    void rejectsUnknownCallbackSource() {
        MockServerWebExchange exchange = callbackExchange("198.51.100.20");

        Assertions.assertThrows(GatewayForbiddenException.class,
                () -> filter.filter(exchange, chain).block());
    }

    @Test
    void bypassesSourceControlWhenDisabled() {
        PaymentCallbackSourceControlFilter disabledFilter = new PaymentCallbackSourceControlFilter(
                new GatewayPaymentCallbackProperties(false, List.of()),
                new ClientIpResolver(new GatewayNetworkProperties(List.of("127.0.0.1"))));
        MockServerWebExchange exchange = callbackExchange("198.51.100.20");

        disabledFilter.filter(exchange, chain).block();

        verify(chain).filter(exchange);
    }

    @Test
    void bypassesNonCallbackRequest() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/payment/PAY1/mock-pay")
                        .remoteAddress(new InetSocketAddress("198.51.100.20", 50000))
                        .build());

        filter.filter(exchange, chain).block();

        verify(chain).filter(exchange);
    }

    private MockServerWebExchange callbackExchange(String remoteIp) {
        return MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/payment/callback")
                        .remoteAddress(new InetSocketAddress(remoteIp, 50000))
                        .build());
    }
}
