package com.example.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "mall.gateway.payment-callback")
public record GatewayPaymentCallbackProperties(
        boolean sourceControlEnabled,
        List<String> allowedSourceIps
) {

    public GatewayPaymentCallbackProperties {
        allowedSourceIps = allowedSourceIps == null ? List.of() : List.copyOf(allowedSourceIps);
    }
}
