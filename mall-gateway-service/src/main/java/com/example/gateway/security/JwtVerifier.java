package com.example.gateway.security;

import com.example.gateway.config.GatewaySecurityProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Optional;

@Component
public class JwtVerifier {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String EXPECTED_ALGORITHM = "HS256";
    private static final String EXPECTED_TOKEN_TYPE = "JWT";
    private static final int MIN_SECRET_BYTES = 32;

    private final byte[] secretBytes;
    private final ObjectMapper objectMapper;

    public JwtVerifier(GatewaySecurityProperties properties, ObjectMapper objectMapper) {
        this.secretBytes = properties.jwtSecret().getBytes(StandardCharsets.UTF_8);
        if (this.secretBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalArgumentException("JWT secret must be at least 32 bytes");
        }
        this.objectMapper = objectMapper;
    }

    public Optional<AuthenticatedUser> verify(String authorizationHeader) {
        if (!StringUtils.hasText(authorizationHeader)
                || !authorizationHeader.startsWith(BEARER_PREFIX)) {
            return Optional.empty();
        }

        String token = authorizationHeader.substring(BEARER_PREFIX.length()).trim();
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            return Optional.empty();
        }

        try {
            byte[] headerBytes = Base64.getUrlDecoder().decode(parts[0]);
            JsonNode header = objectMapper.readTree(headerBytes);
            if (!EXPECTED_ALGORITHM.equals(header.path("alg").asText())
                    || !EXPECTED_TOKEN_TYPE.equals(header.path("typ").asText())) {
                return Optional.empty();
            }

            String signedData = parts[0] + "." + parts[1];
            byte[] actualSignature = Base64.getUrlDecoder().decode(parts[2]);
            byte[] expectedSignature = sign(signedData);
            if (!MessageDigest.isEqual(expectedSignature, actualSignature)) {
                return Optional.empty();
            }

            JsonNode payload = objectMapper.readTree(Base64.getUrlDecoder().decode(parts[1]));
            long expirationTime = payload.path("exp").asLong(0);
            long userId = payload.path("userId").asLong(0);
            if (expirationTime <= 0
                    || System.currentTimeMillis() >= expirationTime
                    || userId <= 0) {
                return Optional.empty();
            }

            String email = payload.path("email").asText(null);
            return Optional.of(new AuthenticatedUser(
                    userId,
                    StringUtils.hasText(email) ? email : null,
                    payload.path("role").asText("USER")
            ));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private byte[] sign(String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secretBytes, "HmacSHA256"));
        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }
}
