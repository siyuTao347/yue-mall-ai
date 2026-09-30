package com.example.item.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "trade.reconciliation")
public record TradeReconciliationProperties(
        @DefaultValue("10") int interruptedMinutes
) {
    public TradeReconciliationProperties {
        if (interruptedMinutes <= 0) {
            interruptedMinutes = 10;
        }
    }
}
