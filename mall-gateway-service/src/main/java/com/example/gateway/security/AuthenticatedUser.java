package com.example.gateway.security;

import java.util.Locale;

public record AuthenticatedUser(Long userId, String email, String role) {

    private static final String ADMIN_ROLE = "ADMIN";
    private static final String DEFAULT_ROLE = "USER";

    public AuthenticatedUser {
        role = role == null || role.isBlank()
                ? DEFAULT_ROLE
                : role.toUpperCase(Locale.ROOT);
    }

    public boolean isAdmin() {
        return ADMIN_ROLE.equals(role);
    }
}
