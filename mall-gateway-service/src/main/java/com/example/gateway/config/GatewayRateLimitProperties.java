package com.example.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

import java.util.List;

@ConfigurationProperties(prefix = "mall.gateway.rate-limit")
public record GatewayRateLimitProperties(
        boolean enabled,
        List<RateLimitRule> rules
) {

    public GatewayRateLimitProperties {
        rules = rules == null || rules.isEmpty()
                ? List.of(RateLimitRule.defaultRule())
                : List.copyOf(rules);
    }

    public enum KeyType {
        USER_OR_IP,
        IP,
        EMAIL,
        SECKILL_PATH,
        SECKILL_EXECUTE
    }

    public record RateLimitRule(
            String id,
            String pathPattern,
            KeyType keyType,
            double replenishRate,
            int burstCapacity
    ) {

        public RateLimitRule {
            if (!StringUtils.hasText(id) || !StringUtils.hasText(pathPattern)) {
                throw new IllegalArgumentException("Rate limit rule id and path pattern are required");
            }
            if (keyType == null || replenishRate <= 0 || burstCapacity <= 0) {
                throw new IllegalArgumentException("Rate limit rule has invalid key type or capacity");
            }
        }

        static RateLimitRule defaultRule() {
            return new RateLimitRule(
                    "default-api",
                    "/api/**",
                    KeyType.USER_OR_IP,
                    1,
                    60
            );
        }
    }
}
