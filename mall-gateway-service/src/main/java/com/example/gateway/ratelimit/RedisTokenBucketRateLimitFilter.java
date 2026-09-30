package com.example.gateway.ratelimit;

import com.example.gateway.config.GatewayRateLimitProperties;
import com.example.gateway.exception.GatewayDependencyUnavailableException;
import com.example.gateway.exception.GatewayRateLimitedException;
import com.example.gateway.web.RequestMatcher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Optional;
import java.nio.charset.StandardCharsets;

@Component
public class RedisTokenBucketRateLimitFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(RedisTokenBucketRateLimitFilter.class);
    private static final RedisScript<Long> TOKEN_BUCKET_SCRIPT = RedisScript.of("""
            local rate = tonumber(ARGV[1])
            local capacity = tonumber(ARGV[2])
            local requested = tonumber(ARGV[3])
            local redis_time = redis.call('time')
            local now_ms = redis_time[1] * 1000 + math.floor(redis_time[2] / 1000)
            local bucket = redis.call('hmget', KEYS[1], 'tokens', 'timestamp')
            local tokens = tonumber(bucket[1])
            local last_refreshed = tonumber(bucket[2])
            if tokens == nil then
                tokens = capacity
            end
            if last_refreshed == nil then
                last_refreshed = now_ms
            end
            local delta_seconds = math.max(0, now_ms - last_refreshed) / 1000
            local filled_tokens = math.min(capacity, tokens + delta_seconds * rate)
            local allowed = filled_tokens >= requested
            local new_tokens = filled_tokens - requested
            if not allowed then
                new_tokens = filled_tokens
            end
            redis.call('hset', KEYS[1], 'tokens', new_tokens, 'timestamp', now_ms)
            redis.call('expire', KEYS[1], math.max(1, math.ceil((capacity / rate) * 2)))
            if allowed then
                return 1
            end
            return 0
            """, Long.class);

    private final GatewayRateLimitProperties properties;
    private final ReactiveStringRedisTemplate redisTemplate;
    private final RequestMatcher requestMatcher;
    private final RateLimitKeyFactory keyFactory;
    private final ObjectMapper objectMapper;

    public RedisTokenBucketRateLimitFilter(
            GatewayRateLimitProperties properties,
            ReactiveStringRedisTemplate redisTemplate,
            RequestMatcher requestMatcher,
            RateLimitKeyFactory keyFactory,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.redisTemplate = redisTemplate;
        this.requestMatcher = requestMatcher;
        this.keyFactory = keyFactory;
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        if (!properties.enabled() || HttpMethod.OPTIONS.equals(request.getMethod())) {
            return chain.filter(exchange);
        }

        Optional<GatewayRateLimitProperties.RateLimitRule> rule = properties.rules().stream()
                .filter(candidate -> requestMatcher.matches(candidate.pathPattern(), request.getPath().value()))
                .findFirst();

        return rule.map(value -> value.keyType() == GatewayRateLimitProperties.KeyType.PAYMENT_CALLBACK
                ? enforceWithPaymentNo(exchange, chain, value)
                : enforce(exchange, chain, value))
                .orElseGet(() -> chain.filter(exchange));
    }

    private Mono<Void> enforceWithPaymentNo(
            ServerWebExchange exchange,
            GatewayFilterChain chain,
            GatewayRateLimitProperties.RateLimitRule rule
    ) {
        return ServerWebExchangeUtils.cacheRequestBodyAndRequest(exchange, cachedRequest ->
                readPaymentNo(cachedRequest).flatMap(paymentNo -> {
                    ServerWebExchange effectiveExchange = exchange.mutate().request(cachedRequest).build();
                    effectiveExchange.getAttributes().put(RateLimitKeyFactory.PAYMENT_NO_ATTRIBUTE, paymentNo);
                    return enforce(effectiveExchange, chain, rule);
                })
        );
    }

    private Mono<String> readPaymentNo(ServerHttpRequest request) {
        return DataBufferUtils.join(request.getBody())
                .map(this::parsePaymentNo)
                .defaultIfEmpty("")
                .onErrorResume(exception -> Mono.just(""));
    }

    private String parsePaymentNo(DataBuffer dataBuffer) {
        try {
            byte[] bytes = new byte[dataBuffer.readableByteCount()];
            dataBuffer.read(bytes);
            JsonNode body = objectMapper.readTree(bytes);
            String paymentNo = body.path("paymentNo").asText("");
            return paymentNo == null ? "" : paymentNo.trim();
        } catch (Exception exception) {
            return "";
        } finally {
            DataBufferUtils.release(dataBuffer);
        }
    }

    private Mono<Void> enforce(
            ServerWebExchange exchange,
            GatewayFilterChain chain,
            GatewayRateLimitProperties.RateLimitRule rule
    ) {
        String redisKey = keyFactory.create(exchange, rule);
        List<Object> arguments = List.of(
                Double.toString(rule.replenishRate()),
                Integer.toString(rule.burstCapacity()),
                "1"
        );

        return redisTemplate.execute(TOKEN_BUCKET_SCRIPT, List.of(redisKey), arguments)
                .next()
                .map(allowed -> Long.valueOf(1L).equals(allowed))
                .defaultIfEmpty(false)
                .onErrorResume(ex -> {
                    log.error("Redis rate limit check failed for rule {}", rule.id(), ex);
                    return Mono.error(new GatewayDependencyUnavailableException());
                })
                .flatMap(allowed -> {
                    if (allowed) {
                        return chain.filter(exchange);
                    }
                    throw new GatewayRateLimitedException();
                });
    }

    @Override
    public int getOrder() {
        return -70;
    }
}
