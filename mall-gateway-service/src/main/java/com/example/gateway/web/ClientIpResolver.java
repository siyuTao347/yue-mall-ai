package com.example.gateway.web;

import com.example.gateway.config.GatewayNetworkProperties;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.List;

@Component
public class ClientIpResolver {

    private static final String UNKNOWN_CLIENT = "unknown";
    private static final String X_FORWARDED_FOR = "X-Forwarded-For";

    private final List<String> trustedProxies;

    public ClientIpResolver(GatewayNetworkProperties properties) {
        this.trustedProxies = properties.trustedProxies();
    }

    public String resolve(ServerHttpRequest request) {
        InetSocketAddress remoteAddress = request.getRemoteAddress();
        if (remoteAddress == null || remoteAddress.getAddress() == null) {
            return UNKNOWN_CLIENT;
        }

        String directIp = remoteAddress.getAddress().getHostAddress();
        if (!trustedProxies.contains(directIp)) {
            return directIp;
        }

        List<String> forwardedHeaders = request.getHeaders().get(X_FORWARDED_FOR);
        List<String> forwardedIps = (forwardedHeaders == null ? List.<String>of() : forwardedHeaders)
                .stream()
                .flatMap(header -> Arrays.stream(header.split(",")))
                .map(this::normalizeIp)
                .filter(ip -> !ip.isBlank())
                .toList();

        for (int index = forwardedIps.size() - 1; index >= 0; index--) {
            String candidate = forwardedIps.get(index);
            if (!trustedProxies.contains(candidate)) {
                return candidate;
            }
        }
        return directIp;
    }

    private String normalizeIp(String value) {
        String ip = value == null ? "" : value.trim();
        if (ip.startsWith("[") && ip.endsWith("]")) {
            ip = ip.substring(1, ip.length() - 1);
        }
        return ip;
    }
}
