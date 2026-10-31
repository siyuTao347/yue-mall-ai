package api.agent.money;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/**
 * 资金调查安全与凭据校验支撑组件
 * <p>
 * 提供服务身份证明 (Service Identity)、委托授权 (Delegation Proof) 以及
 * 范围凭证 (Scope Proof) 的生成与不可伪造验签。
 * 遵循签发者/接收者隔离、受众校验与动作授权约束。
 * </p>
 */
public final class MoneySecuritySupport {

    // 默认测试密钥（彼此独立隔离，生产环境由系统属性或环境变量注入）
    private static final String DEFAULT_SERVICE_SECRET = "DEV_TEST_SERVICE_IDENTITY_SECRET_KEY_2026_HMAC_SHA256";
    private static final String DEFAULT_DELEGATION_SECRET = "DEV_TEST_DELEGATION_PROOF_SECRET_KEY_2026_HMAC_SHA256";
    private static final String DEFAULT_SCOPE_SECRET = "DEV_TEST_SCOPE_PROOF_SECRET_KEY_2026_HMAC_SHA256";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long DEFAULT_EXPIRE_MS = 15 * 60 * 1000L; // 15 分钟有效

    private MoneySecuritySupport() {}

    private static String getServiceSecret() {
        String prop = System.getProperty("money.security.service-secret");
        if (prop != null && !prop.isBlank()) return prop.trim();
        String env = System.getenv("MONEY_SERVICE_SECRET");
        if (env != null && !env.isBlank()) return env.trim();
        return DEFAULT_SERVICE_SECRET;
    }

    private static String getDelegationSecret() {
        String prop = System.getProperty("money.security.delegation-secret");
        if (prop != null && !prop.isBlank()) return prop.trim();
        String env = System.getenv("MONEY_DELEGATION_SECRET");
        if (env != null && !env.isBlank()) return env.trim();
        return DEFAULT_DELEGATION_SECRET;
    }

    private static String getScopeSecret() {
        String prop = System.getProperty("money.security.scope-secret");
        if (prop != null && !prop.isBlank()) return prop.trim();
        String env = System.getenv("MONEY_SCOPE_SECRET");
        if (env != null && !env.isBlank()) return env.trim();
        return DEFAULT_SCOPE_SECRET;
    }

    // =========================================================================
    // 1. 服务间身份证明 (Service Identity Proof)
    // =========================================================================

    public static String createServiceIdentityProof(String callerService) {
        return createServiceIdentityProof(callerService, "mall-money-services");
    }

    public static String createServiceIdentityProof(String callerService, String targetAudience) {
        try {
            long now = System.currentTimeMillis();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", "SERVICE_IDENTITY");
            payload.put("iss", callerService != null ? callerService : "UNKNOWN_SERVICE");
            payload.put("aud", targetAudience != null ? targetAudience : "mall-money-services");
            payload.put("iat", now);
            payload.put("exp", now + DEFAULT_EXPIRE_MS);
            payload.put("nonce", UUID.randomUUID().toString());
            return signToken(payload, getServiceSecret());
        } catch (Exception e) {
            throw new IllegalStateException("生成服务身份证明失败", e);
        }
    }

    public static boolean verifyServiceIdentity(String token, String expectedService) {
        return verifyAndParseServiceIdentity(token, expectedService, null) != null;
    }

    public static ServiceIdentityClaims verifyAndParseServiceIdentity(String token, String expectedService, String expectedAudience) {
        JsonNode node = verifyAndParseToken(token, getServiceSecret());
        if (node == null) return null;
        if (!"SERVICE_IDENTITY".equals(node.path("type").asText())) return null;

        String iss = node.path("iss").asText(null);
        if (expectedService != null && !expectedService.equals(iss)) return null;

        String aud = node.path("aud").asText(null);
        if (expectedAudience != null && !isAudienceMatched(expectedAudience, aud)) return null;

        long exp = node.path("exp").asLong(0);
        long now = System.currentTimeMillis();
        if (now > exp) return null;

        long iat = node.path("iat").asLong(0);
        String nonce = node.path("nonce").asText(null);
        return new ServiceIdentityClaims(iss, aud, exp, iat, nonce);
    }

    // =========================================================================
    // 2. 委托授权证明 (Delegation Proof)
    // =========================================================================

    public static String createDelegationProof(Long actorUserId, String actorRole, String scopeKey) {
        return createDelegationProof(actorUserId, actorRole, scopeKey, "mall-money-services",
                List.of("*"), "GRANT_" + UUID.randomUUID().toString().substring(0, 8));
    }

    public static String createDelegationProof(Long actorUserId, String actorRole, String scopeKey,
                                              String targetAudience, List<String> allowedActions, String grantId) {
        try {
            long now = System.currentTimeMillis();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", "DELEGATION");
            payload.put("iss", "mall-delegation-issuer");
            payload.put("aud", targetAudience != null ? targetAudience : "mall-money-services");
            payload.put("sub", actorUserId != null ? String.valueOf(actorUserId) : "ANONYMOUS");
            payload.put("role", actorRole != null ? actorRole.toUpperCase() : "BUYER");
            payload.put("scopeKey", scopeKey != null ? scopeKey : "");
            payload.put("actions", allowedActions != null && !allowedActions.isEmpty() ? allowedActions : List.of("*"));
            payload.put("grantId", grantId != null ? grantId : "DEFAULT_GRANT");
            payload.put("iat", now);
            payload.put("exp", now + DEFAULT_EXPIRE_MS);
            payload.put("jti", UUID.randomUUID().toString());
            return signToken(payload, getDelegationSecret());
        } catch (Exception e) {
            throw new IllegalStateException("生成委托证明失败", e);
        }
    }

    public static boolean verifyDelegation(String token, Long expectedUserId, String expectedRole, String expectedScopeKey) {
        return verifyAndParseDelegation(token, expectedUserId, expectedRole, expectedScopeKey, null, null) != null;
    }

    public static DelegationClaims verifyAndParseDelegation(String token, Long expectedUserId, String expectedRole,
                                                            String expectedScopeKey, String expectedAudience, String requiredAction) {
        JsonNode node = verifyAndParseToken(token, getDelegationSecret());
        if (node == null) return null;
        if (!"DELEGATION".equals(node.path("type").asText())) return null;

        String sub = node.path("sub").asText(null);
        Long userId = null;
        if (sub != null && !"ANONYMOUS".equalsIgnoreCase(sub)) {
            try {
                userId = Long.parseLong(sub);
            } catch (NumberFormatException ignored) {}
        }
        if (expectedUserId != null && !Objects.equals(expectedUserId, userId)) return null;

        String role = node.path("role").asText("").toUpperCase();
        if (expectedRole != null && !expectedRole.equalsIgnoreCase(role)) return null;

        String scopeKey = node.path("scopeKey").asText("");
        if (expectedScopeKey != null && !expectedScopeKey.isBlank() && !scopeKey.isBlank() && !expectedScopeKey.equals(scopeKey)) {
            return null;
        }

        String aud = node.path("aud").asText(null);
        if (expectedAudience != null && !isAudienceMatched(expectedAudience, aud)) return null;

        List<String> actions = new ArrayList<>();
        if (node.has("actions") && node.path("actions").isArray()) {
            node.path("actions").forEach(a -> actions.add(a.asText()));
        } else {
            actions.add("*");
        }

        if (requiredAction != null && !actions.contains("*") && !actions.contains(requiredAction)) {
            return null;
        }

        long exp = node.path("exp").asLong(0);
        long now = System.currentTimeMillis();
        if (now > exp) return null;

        long iat = node.path("iat").asLong(0);
        String grantId = node.path("grantId").asText(null);
        String jti = node.path("jti").asText(null);

        return new DelegationClaims(userId, role, scopeKey, aud, actions, grantId, exp, iat, jti);
    }

    // =========================================================================
    // 3. 订单范围证明 (Order Scope Proof)
    // =========================================================================

    public static String createScopeProof(String orderNo, Long buyerId, List<String> verifiedAttempts,
                                          List<String> verifiedRefunds, String scopeFingerprint) {
        return createScopeProof(orderNo, buyerId, null, verifiedAttempts, verifiedRefunds, scopeFingerprint, "mall-money-services");
    }

    public static String createScopeProof(String orderNo, Long buyerId, Long sellerId,
                                          List<String> verifiedAttempts, List<String> verifiedRefunds,
                                          String scopeFingerprint, String targetAudience) {
        try {
            long now = System.currentTimeMillis();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", "SCOPE_PROOF");
            payload.put("iss", "mall-order-service");
            payload.put("aud", targetAudience != null ? targetAudience : "mall-money-services");
            payload.put("orderNo", orderNo);
            payload.put("buyerId", buyerId);
            payload.put("sellerId", sellerId);
            payload.put("attempts", verifiedAttempts != null ? verifiedAttempts : Collections.emptyList());
            payload.put("refunds", verifiedRefunds != null ? verifiedRefunds : Collections.emptyList());
            payload.put("fingerprint", scopeFingerprint);
            payload.put("iat", now);
            payload.put("exp", now + DEFAULT_EXPIRE_MS);
            payload.put("jti", UUID.randomUUID().toString());
            return signToken(payload, getScopeSecret());
        } catch (Exception e) {
            throw new IllegalStateException("签发范围证明失败", e);
        }
    }

    public static ScopeProofClaims verifyAndParseScopeProof(String token) {
        return verifyAndParseScopeProof(token, null);
    }

    public static ScopeProofClaims verifyAndParseScopeProof(String token, String expectedAudience) {
        JsonNode node = verifyAndParseToken(token, getScopeSecret());
        if (node == null) return null;
        if (!"SCOPE_PROOF".equals(node.path("type").asText())) return null;
        if (!"mall-order-service".equals(node.path("iss").asText())) return null;

        String aud = node.path("aud").asText(null);
        if (expectedAudience != null && !isAudienceMatched(expectedAudience, aud)) return null;

        long exp = node.path("exp").asLong(0);
        long now = System.currentTimeMillis();
        if (now > exp) return null;

        String orderNo = node.path("orderNo").asText(null);
        Long buyerId = node.hasNonNull("buyerId") ? node.path("buyerId").asLong() : null;
        Long sellerId = node.hasNonNull("sellerId") ? node.path("sellerId").asLong() : null;
        List<String> attempts = new ArrayList<>();
        if (node.has("attempts") && node.path("attempts").isArray()) {
            node.path("attempts").forEach(a -> attempts.add(a.asText()));
        }
        List<String> refunds = new ArrayList<>();
        if (node.has("refunds") && node.path("refunds").isArray()) {
            node.path("refunds").forEach(r -> refunds.add(r.asText()));
        }
        String fingerprint = node.path("fingerprint").asText(null);
        long iat = node.path("iat").asLong(0);

        return new ScopeProofClaims(orderNo, buyerId, sellerId, attempts, refunds, fingerprint, "mall-order-service", aud, exp, iat);
    }

    // =========================================================================
    // 4. 统一入口校验器 (Unified Entry Access Validator)
    // =========================================================================

    public static VerifiedAccess verifyEntryAccess(MoneyAccessContext access,
                                                   String expectedAudience,
                                                   String requiredAction,
                                                   String expectedScopeKey) {
        if (access == null) {
            throw new IllegalArgumentException("访问凭据 (MoneyAccessContext) 不能为空");
        }

        if (access.getServiceIdentityProof() == null || access.getServiceIdentityProof().isBlank()) {
            throw new IllegalArgumentException("服务身份凭据 (serviceIdentityProof) 缺失");
        }
        ServiceIdentityClaims svcClaims = verifyAndParseServiceIdentity(
                access.getServiceIdentityProof(), null, expectedAudience);
        if (svcClaims == null) {
            throw new IllegalArgumentException("服务身份凭据无效、已过期或受众不匹配");
        }

        if (access.getDelegationProof() == null || access.getDelegationProof().isBlank()) {
            throw new IllegalArgumentException("委托授权票据 (delegationProof) 缺失");
        }
        DelegationClaims delClaims = verifyAndParseDelegation(
                access.getDelegationProof(), null, null, expectedScopeKey, expectedAudience, requiredAction);
        if (delClaims == null) {
            throw new IllegalArgumentException("委托授权票据无效、已过期、动作未授权或作用域不匹配");
        }

        return new VerifiedAccess(
                svcClaims.getServiceName(),
                delClaims.getUserId(),
                delClaims.getRole(),
                delClaims.getScopeKey(),
                delClaims.getGrantId(),
                delClaims.getAllowedActions()
        );
    }

    private static boolean isAudienceMatched(String expectedAudience, String actualAudience) {
        if (actualAudience == null) return false;
        if ("*".equals(actualAudience) || "mall-money-services".equalsIgnoreCase(actualAudience)) return true;
        return actualAudience.equalsIgnoreCase(expectedAudience);
    }

    // =========================================================================
    // 5. 事实指纹计算 (SHA-256)
    // =========================================================================

    public static String computeFingerprint(String input) {
        if (input == null) return "";
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            throw new RuntimeException("计算指纹失败", e);
        }
    }

    // =========================================================================
    // 数据载荷结构
    // =========================================================================

    public static class ServiceIdentityClaims {
        private final String serviceName;
        private final String audience;
        private final long expiresAt;
        private final long issuedAt;
        private final String nonce;

        public ServiceIdentityClaims(String serviceName, String audience, long expiresAt, long issuedAt, String nonce) {
            this.serviceName = serviceName;
            this.audience = audience;
            this.expiresAt = expiresAt;
            this.issuedAt = issuedAt;
            this.nonce = nonce;
        }

        public String getServiceName() { return serviceName; }
        public String getAudience() { return audience; }
        public long getExpiresAt() { return expiresAt; }
        public long getIssuedAt() { return issuedAt; }
        public String getNonce() { return nonce; }
    }

    public static class DelegationClaims {
        private final Long userId;
        private final String role;
        private final String scopeKey;
        private final String audience;
        private final List<String> allowedActions;
        private final String grantId;
        private final long expiresAt;
        private final long issuedAt;
        private final String jti;

        public DelegationClaims(Long userId, String role, String scopeKey, String audience,
                                List<String> allowedActions, String grantId, long expiresAt, long issuedAt, String jti) {
            this.userId = userId;
            this.role = role;
            this.scopeKey = scopeKey;
            this.audience = audience;
            this.allowedActions = allowedActions;
            this.grantId = grantId;
            this.expiresAt = expiresAt;
            this.issuedAt = issuedAt;
            this.jti = jti;
        }

        public Long getUserId() { return userId; }
        public String getRole() { return role; }
        public String getScopeKey() { return scopeKey; }
        public String getAudience() { return audience; }
        public List<String> getAllowedActions() { return allowedActions; }
        public String getGrantId() { return grantId; }
        public long getExpiresAt() { return expiresAt; }
        public long getIssuedAt() { return issuedAt; }
        public String getJti() { return jti; }
    }

    public static class ScopeProofClaims {
        private final String orderNo;
        private final Long buyerId;
        private final Long sellerId;
        private final List<String> attempts;
        private final List<String> refunds;
        private final String fingerprint;
        private final String issuer;
        private final String audience;
        private final long expiresAt;
        private final long issuedAt;

        public ScopeProofClaims(String orderNo, Long buyerId, Long sellerId, List<String> attempts,
                                List<String> refunds, String fingerprint, String issuer, String audience,
                                long expiresAt, long issuedAt) {
            this.orderNo = orderNo;
            this.buyerId = buyerId;
            this.sellerId = sellerId;
            this.attempts = attempts;
            this.refunds = refunds;
            this.fingerprint = fingerprint;
            this.issuer = issuer;
            this.audience = audience;
            this.expiresAt = expiresAt;
            this.issuedAt = issuedAt;
        }

        public String getOrderNo() { return orderNo; }
        public Long getBuyerId() { return buyerId; }
        public Long getSellerId() { return sellerId; }
        public List<String> getAttempts() { return attempts; }
        public List<String> getRefunds() { return refunds; }
        public String getFingerprint() { return fingerprint; }
        public String getIssuer() { return issuer; }
        public String getAudience() { return audience; }
        public long getExpiresAt() { return expiresAt; }
        public long getIssuedAt() { return issuedAt; }
    }

    public static class VerifiedAccess {
        private final String callerService;
        private final Long actorUserId;
        private final String actorRole;
        private final String scopeKey;
        private final String grantId;
        private final List<String> allowedActions;

        public VerifiedAccess(String callerService, Long actorUserId, String actorRole,
                              String scopeKey, String grantId, List<String> allowedActions) {
            this.callerService = callerService;
            this.actorUserId = actorUserId;
            this.actorRole = actorRole;
            this.scopeKey = scopeKey;
            this.grantId = grantId;
            this.allowedActions = allowedActions;
        }

        public String getCallerService() { return callerService; }
        public Long getActorUserId() { return actorUserId; }
        public String getActorRole() { return actorRole; }
        public String getScopeKey() { return scopeKey; }
        public String getGrantId() { return grantId; }
        public List<String> getAllowedActions() { return allowedActions; }
    }

    // =========================================================================
    // 内部签名与解析实现
    // =========================================================================

    private static String signToken(Map<String, Object> payload, String secretKey) throws Exception {
        String payloadJson = MAPPER.writeValueAsString(payload);
        String payloadBase64 = Base64.getUrlEncoder().withoutPadding().encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        String signature = hmacSha256(payloadBase64, secretKey);
        return payloadBase64 + "." + signature;
    }

    private static JsonNode verifyAndParseToken(String token, String secretKey) {
        if (token == null || token.isBlank()) return null;
        String[] parts = token.trim().split("\\.");
        if (parts.length != 2) return null;

        try {
            String payloadBase64 = parts[0];
            String expectedSig = hmacSha256(payloadBase64, secretKey);
            if (!expectedSig.equals(parts[1])) return null;

            byte[] decodedBytes = Base64.getUrlDecoder().decode(payloadBase64);
            return MAPPER.readTree(decodedBytes);
        } catch (Exception e) {
            return null;
        }
    }

    private static String hmacSha256(String data, String secret) throws Exception {
        Mac hmac = Mac.getInstance("HmacSHA256");
        SecretKeySpec keySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        hmac.init(keySpec);
        byte[] sigBytes = hmac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(sigBytes);
    }
}
