package com.example.gateway.ratelimit;

import com.example.gateway.config.GatewayNetworkProperties;
import com.example.gateway.config.GatewayRateLimitProperties;
import com.example.gateway.security.AuthenticatedUser;
import com.example.gateway.security.AuthenticationGlobalFilter;
import com.example.gateway.web.ClientIpResolver;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import java.net.InetSocketAddress;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitKeyFactoryTest {

    private final RateLimitKeyFactory keyFactory = new RateLimitKeyFactory(
            new ClientIpResolver(new GatewayNetworkProperties(List.of("127.0.0.1")))
    );

    @Test
    void createsStableEmailKey() {
        MockServerWebExchange firstExchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/user/sendEmailCode?email=user%40example.com")
                        .remoteAddress(new InetSocketAddress("127.0.0.1", 50000))
                        .build()
        );
        MockServerWebExchange secondExchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/user/sendEmailCode?email=user%40example.com")
                        .remoteAddress(new InetSocketAddress("127.0.0.1", 50001))
                        .build()
        );

        String firstKey = keyFactory.create(firstExchange, emailRule());
        String secondKey = keyFactory.create(secondExchange, emailRule());

        assertThat(firstKey).startsWith("mall:gateway:rate-limit:send-email-code:");
        assertThat(firstKey).isEqualTo(secondKey);
    }

    @Test
    void createsDifferentKeysForDifferentEmails() {
        MockServerWebExchange firstExchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/user/sendEmailCode?email=user%40example.com").build()
        );
        MockServerWebExchange secondExchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/user/sendEmailCode?email=other%40example.com").build()
        );

        assertThat(keyFactory.create(firstExchange, emailRule()))
                .isNotEqualTo(keyFactory.create(secondExchange, emailRule()));
    }

    @Test
    void usesUserIdWhenAvailable() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/trade/orders")
                        .remoteAddress(new InetSocketAddress("127.0.0.1", 50000))
                        .build()
        );
        exchange.getAttributes().put(
                AuthenticationGlobalFilter.USER_ATTRIBUTE,
                new AuthenticatedUser(101L, "user@example.com", "USER")
        );

        String userKey = keyFactory.create(exchange, defaultRule());
        exchange.getAttributes().remove(AuthenticationGlobalFilter.USER_ATTRIBUTE);
        String anonymousKey = keyFactory.create(exchange, defaultRule());

        assertThat(userKey).isNotEqualTo(anonymousKey);
    }

    @Test
    void createsDifferentKeysForDifferentSeckillItems() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/seckill/getPath?itemId=1").build()
        );
        exchange.getAttributes().put(
                AuthenticationGlobalFilter.USER_ATTRIBUTE,
                new AuthenticatedUser(101L, "user@example.com", "USER")
        );

        String firstKey = keyFactory.create(exchange, seckillPathRule());
        exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/seckill/getPath?itemId=2").build()
        );
        exchange.getAttributes().put(
                AuthenticationGlobalFilter.USER_ATTRIBUTE,
                new AuthenticatedUser(101L, "user@example.com", "USER")
        );
        String secondKey = keyFactory.create(exchange, seckillPathRule());

        assertThat(firstKey).isNotEqualTo(secondKey);
    }

    private GatewayRateLimitProperties.RateLimitRule emailRule() {
        return new GatewayRateLimitProperties.RateLimitRule(
                "send-email-code",
                "/api/user/sendEmailCode",
                GatewayRateLimitProperties.KeyType.EMAIL,
                0.0167,
                1
        );
    }

    private GatewayRateLimitProperties.RateLimitRule defaultRule() {
        return new GatewayRateLimitProperties.RateLimitRule(
                "default-api",
                "/api/**",
                GatewayRateLimitProperties.KeyType.USER_OR_IP,
                1,
                60
        );
    }

    private GatewayRateLimitProperties.RateLimitRule seckillPathRule() {
        return new GatewayRateLimitProperties.RateLimitRule(
                "seckill-path",
                "/api/seckill/getPath",
                GatewayRateLimitProperties.KeyType.SECKILL_PATH,
                2,
                4
        );
    }
}
