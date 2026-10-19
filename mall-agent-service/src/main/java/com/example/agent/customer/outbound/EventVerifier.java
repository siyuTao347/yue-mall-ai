package com.example.agent.customer.outbound;

import com.example.agent.customer.CustomerDatabase;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

import static com.example.agent.customer.CustomerJson.error;

@Component
public class EventVerifier {

    private final CustomerDatabase db;
    // 默认开发密钥
    private static final String DEFAULT_SECRET = "valor-mall-outbound-webhook-secret-key-2026";

    public EventVerifier(CustomerDatabase db) {
        this.db = db;
    }

    public void verify(String source, String method, String canonicalPath, String timestampStr,
                       String nonce, String rawBody, String signature) {
        if (timestampStr == null || nonce == null || signature == null) {
            throw error(401, "WEBHOOK_AUTH_MISSING", "缺少签名头信息");
        }
        long ts;
        try {
            ts = Long.parseLong(timestampStr);
        } catch (Exception e) {
            throw error(400, "TIMESTAMP_INVALID", "时间戳格式错误");
        }
        long now = Instant.now().getEpochSecond();
        if (Math.abs(now - ts) > 300) { // 5分钟窗口
            throw error(401, "TIMESTAMP_EXPIRED", "请求时间戳已超时");
        }

        // 检查防重放 Nonce
        try {
            db.mysql.update(
                    "INSERT INTO t_agent_event_nonce(source_code, nonce_hash, expires_time) " +
                            "VALUES(?, ?, TIMESTAMPADD(MINUTE, 10, now(3)))",
                    source, nonce
            );
        } catch (Exception duplicateNonce) {
            throw error(409, "NONCE_REPLAYED", "检测到重放请求 Nonce");
        }

        // 校验 HMAC-SHA256
        try {
            String bodySha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(rawBody.getBytes(StandardCharsets.UTF_8)));
            String payloadToSign = method + "\n" + canonicalPath + "\n" + timestampStr + "\n" + nonce + "\n" + bodySha256;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(DEFAULT_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String expected = HexFormat.of().formatHex(mac.doFinal(payloadToSign.getBytes(StandardCharsets.UTF_8)));
            if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8))) {
                throw error(401, "SIGNATURE_MISMATCH", "签名校验失败");
            }
        } catch (com.example.agent.exception.RagApiException ragEx) {
            throw ragEx;
        } catch (Exception e) {
            throw error(500, "CRYPTO_ERROR", "验签计算异常");
        }
    }
}
