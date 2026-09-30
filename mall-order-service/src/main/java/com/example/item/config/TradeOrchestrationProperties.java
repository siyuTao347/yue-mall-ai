package com.example.item.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.math.BigDecimal;

@ConfigurationProperties(prefix = "trade.orchestration")
public record TradeOrchestrationProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("100") int recoverBatchSize,
        @DefaultValue("60") int lockSeconds,
        @DefaultValue("5") int maxRetryCount,
        @DefaultValue("30") int retryDelaySeconds,
        @DefaultValue("trade-orchestration-task-topic") String topic,
        @DefaultValue("30") int deliveryTimeoutMinutes,
        @DefaultValue("15") int paymentExpireMinutes,
        @DefaultValue("2") BigDecimal feeRatePercent,
        @DefaultValue("0.01") BigDecimal minFee
) {
}
