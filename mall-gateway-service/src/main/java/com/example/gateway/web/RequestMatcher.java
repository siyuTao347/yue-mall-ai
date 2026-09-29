package com.example.gateway.web;

import org.springframework.http.HttpMethod;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;

import java.util.Collection;
import java.util.Locale;
import java.util.Set;

@Component
public class RequestMatcher {

    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();
    private static final Set<String> HTTP_METHODS = Set.of(
            "GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS"
    );

    public boolean matches(Collection<String> rules, ServerHttpRequest request) {
        return rules.stream().anyMatch(rule -> matches(rule, request));
    }

    public boolean matches(String pattern, String path) {
        return PATH_MATCHER.match(pattern, path);
    }

    private boolean matches(String rule, ServerHttpRequest request) {
        String method = "ANY";
        String pathPattern = rule;

        int separatorIndex = rule.indexOf(':');
        if (separatorIndex > 0) {
            String candidate = rule.substring(0, separatorIndex).trim().toUpperCase(Locale.ROOT);
            if (HTTP_METHODS.contains(candidate) || "ANY".equals(candidate)) {
                method = candidate;
                pathPattern = rule.substring(separatorIndex + 1).trim();
            }
        }

        HttpMethod requestMethod = request.getMethod();
        if (requestMethod == null) {
            return false;
        }
        return ("ANY".equals(method) || method.equals(requestMethod.name()))
                && PATH_MATCHER.match(pathPattern, request.getPath().value());
    }
}
