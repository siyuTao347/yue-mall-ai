package api.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

public class JwtUtil {

    private static final String SECRET = "VALOR_MALL_JWT_SECRET_KEY_2026_HIGH_CONCURRENCY";
    private static final String HEADER_JSON = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";
    private static final long EXPIRE_MS = 7L * 24 * 3600 * 1000; // 7 天过期
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 签发 JWT Token
     */
    public static String generateToken(Long userId, String email, String nickname) {
        try {
            long now = System.currentTimeMillis();
            long exp = now + EXPIRE_MS;

            Map<String, Object> payload = new HashMap<>();
            payload.put("userId", userId);
            payload.put("email", email);
            payload.put("nickname", nickname != null ? nickname : "VALOR特工");
            payload.put("iat", now);
            payload.put("exp", exp);

            String headerBase64 = base64UrlEncode(HEADER_JSON.getBytes(StandardCharsets.UTF_8));
            String payloadBase64 = base64UrlEncode(MAPPER.writeValueAsBytes(payload));
            String dataToSign = headerBase64 + "." + payloadBase64;
            String signature = sign(dataToSign);

            return dataToSign + "." + signature;
        } catch (Exception e) {
            throw new RuntimeException("JWT 签发失败", e);
        }
    }

    /**
     * 校验并提取 UserId (校验失败或过期返回 null)
     */
    public static Long parseUserId(String token) {
        if (token == null || token.trim().isEmpty()) {
            return null;
        }
        if (token.startsWith("Bearer ")) {
            token = token.substring(7).trim();
        }

        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            return null;
        }

        try {
            String dataToSign = parts[0] + "." + parts[1];
            String expectedSig = sign(dataToSign);
            if (!expectedSig.equals(parts[2])) {
                return null; // 签名不匹配
            }

            byte[] payloadBytes = Base64.getUrlDecoder().decode(parts[1]);
            JsonNode node = MAPPER.readTree(payloadBytes);

            long exp = node.path("exp").asLong(0);
            if (System.currentTimeMillis() > exp) {
                return null; // Token 已过期
            }

            return node.path("userId").asLong();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 解析 Payload JsonNode
     */
    public static JsonNode parsePayload(String token) {
        if (token == null || token.trim().isEmpty()) return null;
        if (token.startsWith("Bearer ")) token = token.substring(7).trim();
        String[] parts = token.split("\\.");
        if (parts.length != 3) return null;
        try {
            byte[] payloadBytes = Base64.getUrlDecoder().decode(parts[1]);
            return MAPPER.readTree(payloadBytes);
        } catch (Exception e) {
            return null;
        }
    }

    private static String sign(String data) throws Exception {
        Mac hmac = Mac.getInstance("HmacSHA256");
        SecretKeySpec keySpec = new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        hmac.init(keySpec);
        byte[] sigBytes = hmac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        return base64UrlEncode(sigBytes);
    }

    private static String base64UrlEncode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
