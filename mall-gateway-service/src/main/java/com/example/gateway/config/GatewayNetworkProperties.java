package com.example.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "mall.gateway.network")
public record GatewayNetworkProperties(List<String> trustedProxies) {

    public GatewayNetworkProperties {
        trustedProxies = trustedProxies == null || trustedProxies.isEmpty()
                ? List.of("127.0.0.1", "::1")
                : List.copyOf(trustedProxies);
    }
}
