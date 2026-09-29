package com.example.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "mall.gateway.security")
public record GatewaySecurityProperties(
        String jwtSecret,
        List<String> publicPaths,
        List<String> adminPaths
) {

    public GatewaySecurityProperties {
        jwtSecret = jwtSecret == null ? "" : jwtSecret;
        publicPaths = publicPaths == null ? List.of() : List.copyOf(publicPaths);
        adminPaths = adminPaths == null ? List.of() : List.copyOf(adminPaths);
    }
}
