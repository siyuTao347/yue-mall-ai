package com.example.gateway.security;

import com.example.gateway.config.GatewaySecurityProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class JwtVerifierTest {

    private final JwtVerifier jwtVerifier = new JwtVerifier(
            new GatewaySecurityProperties(JwtTestTokens.SECRET, List.of(), List.of()),
            new ObjectMapper()
    );

    @Test
    void verifiesValidBearerToken() {
        String token = JwtTestTokens.create(
                101L,
                "user@example.com",
                "USER",
                System.currentTimeMillis() + 60_000L
        );

        Optional<AuthenticatedUser> result = jwtVerifier.verify("Bearer " + token);

        assertThat(result).isPresent();
        assertThat(result.get().userId()).isEqualTo(101L);
        assertThat(result.get().email()).isEqualTo("user@example.com");
        assertThat(result.get().role()).isEqualTo("USER");
    }

    @Test
    void rejectsExpiredToken() {
        String token = JwtTestTokens.create(
                101L,
                "user@example.com",
                "USER",
                System.currentTimeMillis() - 1_000L
        );

        assertThat(jwtVerifier.verify("Bearer " + token)).isEmpty();
    }

    @Test
    void rejectsTamperedSignature() {
        String token = JwtTestTokens.create(
                101L,
                "user@example.com",
                "ADMIN",
                System.currentTimeMillis() + 60_000L
        );
        String tamperedToken = token.substring(0, token.length() - 2) + "aa";

        assertThat(jwtVerifier.verify("Bearer " + tamperedToken)).isEmpty();
    }

    @Test
    void rejectsTokenWithoutBearerScheme() {
        String token = JwtTestTokens.create(
                101L,
                "user@example.com",
                "USER",
                System.currentTimeMillis() + 60_000L
        );

        assertThat(jwtVerifier.verify(token)).isEmpty();
    }
}
