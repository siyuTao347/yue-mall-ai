package api.risk;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.UUID;

public final class RiskSupport {
    private RiskSupport() {
    }

    public static String nextEventNo(String scene) {
        return "RE-" + scene + "-" + System.currentTimeMillis() + "-"
                + UUID.randomUUID().toString().replace("-", "");
    }

    public static String hmacSha256(String value, String salt) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(salt.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("风控身份哈希计算失败", e);
        }
    }

    public static String sha256(String value) {
        if (value == null) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("内容哈希计算失败", e);
        }
    }

    public static String toRiskStatus(String action) {
        if (action == null) {
            return "NORMAL";
        }
        return switch (action) {
            case RiskDecisionResult.ACTION_WATCH -> "WATCH";
            case RiskDecisionResult.ACTION_VERIFY -> "VERIFY";
            case RiskDecisionResult.ACTION_LIMIT -> "LIMITED";
            case RiskDecisionResult.ACTION_MANUAL_REVIEW -> "MANUAL_REVIEW";
            case RiskDecisionResult.ACTION_REJECT -> "REJECTED";
            case RiskDecisionResult.ACTION_FREEZE -> "FROZEN";
            default -> "NORMAL";
        };
    }

    public static long hoursBetween(LocalDateTime from, LocalDateTime to) {
        if (from == null) {
            return 0;
        }
        return Math.max(0, Duration.between(from, to == null ? LocalDateTime.now() : to).toHours());
    }
}
