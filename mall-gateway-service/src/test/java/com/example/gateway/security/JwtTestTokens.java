package com.example.gateway.security;

import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

final class JwtTestTokens {

    static final String SECRET = "0123456789abcdef0123456789abcdef";

    private static final String HEADER_JSON = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private JwtTestTokens() {
    }

    static String create(Long userId, String email, String role, long expirationTime) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("userId", userId);
            payload.put("email", email);
            payload.put("role", role);
            payload.put("exp", expirationTime);

            String header = base64UrlEncode(HEADER_JSON.getBytes(StandardCharsets.UTF_8));
            String body = base64UrlEncode(OBJECT_MAPPER.writeValueAsBytes(payload));
            String signedData = header + "." + body;
            return signedData + "." + base64UrlEncode(sign(signedData));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static byte[] sign(String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }

    private static String base64UrlEncode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
