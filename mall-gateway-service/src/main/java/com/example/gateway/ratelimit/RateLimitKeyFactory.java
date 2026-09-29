package com.example.gateway.ratelimit;

import com.example.gateway.config.GatewayRateLimitProperties;
import com.example.gateway.security.AuthenticationGlobalFilter;
import com.example.gateway.security.AuthenticatedUser;
import com.example.gateway.web.ClientIpResolver;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

@Component
public class RateLimitKeyFactory {

    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();
    private static final String SECKILL_EXECUTE_PATTERN = "/api/seckill/{pathToken}/doSeckill";

    private final ClientIpResolver clientIpResolver;

    public RateLimitKeyFactory(ClientIpResolver clientIpResolver) {
        this.clientIpResolver = clientIpResolver;
    }

    public String create(ServerWebExchange exchange, GatewayRateLimitProperties.RateLimitRule rule) {
        ServerHttpRequest request = exchange.getRequest();
        String identity = switch (rule.keyType()) {
            case USER_OR_IP -> {
                Long userId = userId(exchange);
                yield userId == null
                        ? "ip:" + clientIpResolver.resolve(request)
                        : "user:" + userId;
            }
            case IP -> "ip:" + clientIpResolver.resolve(request);
            case EMAIL -> emailIdentity(exchange, request);
            case SECKILL_PATH -> "seckill-path:" + userId(exchange)
                    + ":" + request.getQueryParams().getFirst("itemId");
            case SECKILL_EXECUTE -> "seckill-execute:" + userId(exchange)
                    + ":" + pathToken(request)
                    + ":" + request.getQueryParams().getFirst("itemId");
        };

        String identityHash = sha256Hex(identity.getBytes(StandardCharsets.UTF_8));
        return "mall:gateway:rate-limit:" + rule.id() + ":" + identityHash;
    }

    private String emailIdentity(ServerWebExchange exchange, ServerHttpRequest request) {
        String email = request.getQueryParams().getFirst("email");
        if (email != null && !email.isBlank()) {
            return "email:" + email.trim();
        }
        return exchange.getAttribute(AuthenticationGlobalFilter.USER_ATTRIBUTE) instanceof AuthenticatedUser user
                && user.email() != null
                ? "email:" + user.email()
                : "ip:" + clientIpResolver.resolve(request);
    }

    private Long userId(ServerWebExchange exchange) {
        return exchange.getAttribute(AuthenticationGlobalFilter.USER_ATTRIBUTE) instanceof AuthenticatedUser user
                ? user.userId()
                : null;
    }

    private String pathToken(ServerHttpRequest request) {
        Map<String, String> variables = PATH_MATCHER.extractUriTemplateVariables(
                SECKILL_EXECUTE_PATTERN,
                request.getPath().value()
        );
        return variables.getOrDefault("pathToken", "unknown");
    }

    private String sha256Hex(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to create rate limit key", exception);
        }
    }
}
