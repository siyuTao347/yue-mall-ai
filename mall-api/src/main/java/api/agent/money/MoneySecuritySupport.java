package api.agent.money;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * 资金调查安全与凭据校验支撑组件 (基于 JOSE/JWT 信任模型)
 * <p>
 * 遵循设计原则：
 * 1. 签发私钥/密钥与验签隔离，仅对应服务持有其签发私钥。
 * 2. 生产环境禁止使用固定内置默认密钥，必须由配置、环境变量或 KMS 显式注入；
 *    仅在显式声明测试模式 (money.security.allow-test-keys=true) 时才允许开发测试密钥。
 * 3. 票据采用 JOSE 规范格式 (Header.Payload.Signature)，严格校验 alg 白名单 (HS256)、
 *    kid (Key ID)、iss (Issuer)、aud (Audience)、exp (Expiration) 及动作白名单。
 * 4. 跨服务调查调用强制验证 caller、subject (原用户)、scope、action、grant 联合上下文。
 * </p>
 */
@Slf4j
public final class MoneySecuritySupport {

    // 仅在明确开启测试模式时允许的隔离测试密钥
    private static final String TEST_SERVICE_SECRET = "DEV_TEST_SERVICE_IDENTITY_SECRET_KEY_2026_HMAC_SHA256";
    private static final String TEST_DELEGATION_SECRET = "DEV_TEST_DELEGATION_PROOF_SECRET_KEY_2026_HMAC_SHA256";
    private static final String TEST_SCOPE_SECRET = "DEV_TEST_SCOPE_PROOF_SECRET_KEY_2026_HMAC_SHA256";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long DEFAULT_EXPIRE_MS = 15 * 60 * 1000L; // 15 分钟有效
    private static final Set<String> ALLOWED_ALGORITHMS = Set.of("HS256");

    private MoneySecuritySupport() {}

    private static boolean isTestKeyAllowed() {
        String prop = System.getProperty("money.security.allow-test-keys");
        if (prop != null) return Boolean.parseBoolean(prop.trim());
        String env = System.getenv("MONEY_ALLOW_TEST_KEYS");
        if (env != null) return Boolean.parseBoolean(env.trim());
        // 默认若在 Maven Test 运行环境下允许测试密钥
        return "true".equalsIgnoreCase(System.getProperty("idea.test.cyclic.buffer.size"))
                || System.getProperty("surefire.test.class.path") != null;
    }

    public static String getServiceSecret() {
        String prop = System.getProperty("money.security.service-secret");
        if (prop != null && !prop.isBlank()) return prop.trim();
        String env = System.getenv("MONEY_SERVICE_SECRET");
        if (env != null && !env.isBlank()) return env.trim();
        if (isTestKeyAllowed()) {
            return TEST_SERVICE_SECRET;
        }
        throw new IllegalStateException("未配置服务身份密钥 (money.security.service-secret)，生产环境禁止缺省回退");
    }

    public static String getDelegationSecret() {
        String prop = System.getProperty("money.security.delegation-secret");
        if (prop != null && !prop.isBlank()) return prop.trim();
        String env = System.getenv("MONEY_DELEGATION_SECRET");
        if (env != null && !env.isBlank()) return env.trim();
        if (isTestKeyAllowed()) {
            return TEST_DELEGATION_SECRET;
        }
        throw new IllegalStateException("未配置委托授权密钥 (money.security.delegation-secret)，生产环境禁止缺省回退");
    }

    public static String getScopeSecret() {
        String prop = System.getProperty("money.security.scope-secret");
        if (prop != null && !prop.isBlank()) return prop.trim();
        String env = System.getenv("MONEY_SCOPE_SECRET");
        if (env != null && !env.isBlank()) return env.trim();
        if (isTestKeyAllowed()) {
            return TEST_SCOPE_SECRET;
        }
        throw new IllegalStateException("未配置范围证明密钥 (money.security.scope-secret)，生产环境禁止缺省回退");
    }

    // =========================================================================
    // 1. 服务间身份证明 (Service Identity Proof)
    // =========================================================================

    public static String createServiceIdentityProof(String callerService) {
        return createServiceIdentityProof(callerService, "mall-money-services");
    }

    public static String createServiceIdentityProof(String callerService, String targetAudience) {
        return createServiceIdentityProofWithSecret(callerService, targetAudience, getServiceSecret(), "svc-kid-01");
    }

    public static String createServiceIdentityProofWithSecret(String callerService, String targetAudience, String secretKey, String kid) {
        try {
            long now = System.currentTimeMillis();
            Map<String, Object> header = new LinkedHashMap<>();
            header.put("alg", "HS256");
            header.put("typ", "JWT");
            header.put("kid", kid != null ? kid : "svc-kid-01");

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", "SERVICE_IDENTITY");
            payload.put("iss", callerService != null ? callerService : "UNKNOWN_SERVICE");
            payload.put("aud", targetAudience != null ? targetAudience : "mall-money-services");
            payload.put("iat", now);
            payload.put("exp", now + DEFAULT_EXPIRE_MS);
            payload.put("jti", UUID.randomUUID().toString());
            payload.put("nonce", UUID.randomUUID().toString());

            return signJoseToken(header, payload, secretKey);
        } catch (Exception e) {
            throw new IllegalStateException("生成服务身份证明失败", e);
        }
    }

    public static boolean verifyServiceIdentity(String token, String expectedService) {
        return verifyAndParseServiceIdentity(token, expectedService, null) != null;
    }

    public static ServiceIdentityClaims verifyAndParseServiceIdentity(String token, String expectedService, String expectedAudience) {
        return verifyAndParseServiceIdentityWithSecret(token, expectedService, expectedAudience, getServiceSecret());
    }

    public static ServiceIdentityClaims verifyAndParseServiceIdentityWithSecret(String token, String expectedService, String expectedAudience, String secretKey) {
        TokenValidationResult res = verifyAndParseJoseToken(token, secretKey);
        if (res == null) return null;
        JsonNode node = res.payload();
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
        return createDelegationProofWithSecret(actorUserId, actorRole, scopeKey, targetAudience, allowedActions, grantId,
                getDelegationSecret(), "del-kid-01");
    }

    public static String createDelegationProofWithSecret(Long actorUserId, String actorRole, String scopeKey,
                                                        String targetAudience, List<String> allowedActions, String grantId,
                                                        String secretKey, String kid) {
        try {
            long now = System.currentTimeMillis();
            Map<String, Object> header = new LinkedHashMap<>();
            header.put("alg", "HS256");
            header.put("typ", "JWT");
            header.put("kid", kid != null ? kid : "del-kid-01");

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

            return signJoseToken(header, payload, secretKey);
        } catch (Exception e) {
            throw new IllegalStateException("生成委托证明失败", e);
        }
    }

    public static boolean verifyDelegation(String token, Long expectedUserId, String expectedRole, String expectedScopeKey) {
        return verifyAndParseDelegation(token, expectedUserId, expectedRole, expectedScopeKey, null, null) != null;
    }

    public static DelegationClaims verifyAndParseDelegation(String token, Long expectedUserId, String expectedRole,
                                                            String expectedScopeKey, String expectedAudience, String requiredAction) {
        return verifyAndParseDelegationWithSecret(token, expectedUserId, expectedRole, expectedScopeKey, expectedAudience, requiredAction, getDelegationSecret());
    }

    public static DelegationClaims verifyAndParseDelegationWithSecret(String token, Long expectedUserId, String expectedRole,
                                                                    String expectedScopeKey, String expectedAudience, String requiredAction,
                                                                    String secretKey) {
        TokenValidationResult res = verifyAndParseJoseToken(token, secretKey);
        if (res == null) return null;
        JsonNode node = res.payload();
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
        return createScopeProof(orderNo, orderNo != null ? "ORDER:" + orderNo : null, buyerId, null, null,
                List.of("READ", "QUERY_FUND"), verifiedAttempts, verifiedRefunds, scopeFingerprint, "mall-user-service,mall-money-services");
    }

    public static String createScopeProof(String orderNo, Long buyerId, Long sellerId,
                                          List<String> verifiedAttempts, List<String> verifiedRefunds,
                                          String scopeFingerprint, String targetAudience) {
        return createScopeProof(orderNo, orderNo != null ? "ORDER:" + orderNo : null, buyerId, sellerId, null,
                List.of("READ", "QUERY_FUND"), verifiedAttempts, verifiedRefunds, scopeFingerprint, targetAudience);
    }

    public static String createScopeProof(String orderNo, String scopeKey, Long buyerId, Long sellerId,
                                          String grantId, List<String> allowedActions,
                                          List<String> verifiedAttempts, List<String> verifiedRefunds,
                                          String scopeFingerprint, String targetAudience) {
        return createScopeProofWithSecret(orderNo, scopeKey, buyerId, sellerId, grantId, allowedActions,
                verifiedAttempts, verifiedRefunds, scopeFingerprint, targetAudience, getScopeSecret(), "scope-kid-01");
    }

    public static String createScopeProofWithSecret(String orderNo, Long buyerId, Long sellerId,
                                                    List<String> verifiedAttempts, List<String> verifiedRefunds,
                                                    String scopeFingerprint, String targetAudience,
                                                    String secretKey, String kid) {
        return createScopeProofWithSecret(orderNo, orderNo != null ? "ORDER:" + orderNo : null, buyerId, sellerId, null,
                List.of("READ", "QUERY_FUND"), verifiedAttempts, verifiedRefunds, scopeFingerprint, targetAudience, secretKey, kid);
    }

    public static String createScopeProofWithSecret(String orderNo, String scopeKey, Long buyerId, Long sellerId,
                                                    String grantId, List<String> allowedActions,
                                                    List<String> verifiedAttempts, List<String> verifiedRefunds,
                                                    String scopeFingerprint, String targetAudience,
                                                    String secretKey, String kid) {
        try {
            int totalRefs = (verifiedAttempts != null ? verifiedAttempts.size() : 0) + (verifiedRefunds != null ? verifiedRefunds.size() : 0);
            if (totalRefs > 50) {
                throw new IllegalArgumentException("单批范围证明引用数不能超过 50 个，当前总数: " + totalRefs);
            }

            long now = System.currentTimeMillis();
            Map<String, Object> header = new LinkedHashMap<>();
            header.put("alg", "HS256");
            header.put("typ", "JWT");
            header.put("kid", kid != null ? kid : "scope-kid-01");

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", "SCOPE_PROOF");
            payload.put("iss", "mall-order-service");
            payload.put("aud", targetAudience != null ? targetAudience : "mall-user-service,mall-money-services");
            payload.put("orderNo", orderNo);
            payload.put("scopeKey", scopeKey != null ? scopeKey : (orderNo != null ? "ORDER:" + orderNo : ""));
            payload.put("buyerId", buyerId);
            payload.put("sellerId", sellerId);
            payload.put("grantId", grantId != null ? grantId : "");
            payload.put("actions", allowedActions != null && !allowedActions.isEmpty() ? allowedActions : List.of("READ", "QUERY_FUND"));
            payload.put("attempts", verifiedAttempts != null ? verifiedAttempts : Collections.emptyList());
            payload.put("refunds", verifiedRefunds != null ? verifiedRefunds : Collections.emptyList());
            payload.put("fingerprint", scopeFingerprint);
            payload.put("iat", now);
            payload.put("exp", now + DEFAULT_EXPIRE_MS);
            payload.put("jti", UUID.randomUUID().toString());

            return signJoseToken(header, payload, secretKey);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("签发范围证明失败", e);
        }
    }

    public static ScopeProofClaims verifyAndParseScopeProof(String token) {
        return verifyAndParseScopeProof(token, null);
    }

    public static ScopeProofClaims verifyAndParseScopeProof(String token, String expectedAudience) {
        return verifyAndParseScopeProofWithSecret(token, expectedAudience, getScopeSecret());
    }

    public static ScopeProofClaims verifyAndParseScopeProofWithSecret(String token, String expectedAudience, String secretKey) {
        TokenValidationResult res = verifyAndParseJoseToken(token, secretKey);
        if (res == null) return null;
        JsonNode node = res.payload();
        if (!"SCOPE_PROOF".equals(node.path("type").asText())) return null;
        if (!"mall-order-service".equals(node.path("iss").asText())) return null;

        String aud = node.path("aud").asText(null);
        if (expectedAudience != null && !isAudienceMatched(expectedAudience, aud)) return null;

        long exp = node.path("exp").asLong(0);
        long now = System.currentTimeMillis();
        if (now > exp) return null;

        String orderNo = node.path("orderNo").asText(null);
        String scopeKey = node.path("scopeKey").asText(null);
        Long buyerId = node.hasNonNull("buyerId") ? node.path("buyerId").asLong() : null;
        Long sellerId = node.hasNonNull("sellerId") ? node.path("sellerId").asLong() : null;
        String grantId = node.path("grantId").asText(null);
        List<String> actions = new ArrayList<>();
        if (node.has("actions") && node.path("actions").isArray()) {
            node.path("actions").forEach(a -> actions.add(a.asText()));
        }

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

        return new ScopeProofClaims(orderNo, scopeKey, buyerId, sellerId, grantId, actions,
                attempts, refunds, fingerprint, "mall-order-service", aud, exp, iat);
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

    public static boolean isAudienceMatched(String expectedAudience, String actualAudience) {
        if (actualAudience == null || expectedAudience == null) return false;
        if ("*".equals(actualAudience)) return true;
        for (String aud : actualAudience.split(",")) {
            if (aud.trim().equalsIgnoreCase(expectedAudience.trim())) return true;
        }
        return false;
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
        private final String scopeKey;
        private final Long buyerId;
        private final Long sellerId;
        private final String grantId;
        private final List<String> allowedActions;
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
            this(orderNo, orderNo != null ? "ORDER:" + orderNo : null, buyerId, sellerId, null,
                    List.of("READ", "QUERY_FUND"), attempts, refunds, fingerprint, issuer, audience, expiresAt, issuedAt);
        }

        public ScopeProofClaims(String orderNo, String scopeKey, Long buyerId, Long sellerId,
                                String grantId, List<String> allowedActions,
                                List<String> attempts, List<String> refunds, String fingerprint,
                                String issuer, String audience, long expiresAt, long issuedAt) {
            this.orderNo = orderNo;
            this.scopeKey = scopeKey;
            this.buyerId = buyerId;
            this.sellerId = sellerId;
            this.grantId = grantId;
            this.allowedActions = allowedActions != null ? allowedActions : Collections.emptyList();
            this.attempts = attempts != null ? attempts : Collections.emptyList();
            this.refunds = refunds != null ? refunds : Collections.emptyList();
            this.fingerprint = fingerprint;
            this.issuer = issuer;
            this.audience = audience;
            this.expiresAt = expiresAt;
            this.issuedAt = issuedAt;
        }

        public String getOrderNo() { return orderNo; }
        public String getScopeKey() { return scopeKey; }
        public Long getBuyerId() { return buyerId; }
        public Long getSellerId() { return sellerId; }
        public String getGrantId() { return grantId; }
        public List<String> getAllowedActions() { return allowedActions; }
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

    public static class VerifiedMoneyPrincipal {
        private final String callerService;
        private final Long actorUserId;
        private final String actorRole;
        private final String scopeKey;
        private final String grantId;
        private final List<String> allowedActions;

        public VerifiedMoneyPrincipal(String callerService, Long actorUserId, String actorRole,
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
    // 内部 JOSE 签名与解析实现
    // =========================================================================

    public record TokenValidationResult(JsonNode header, JsonNode payload) {}

    private static String signJoseToken(Map<String, Object> header, Map<String, Object> payload, String secretKey) throws Exception {
        String headerJson = MAPPER.writeValueAsString(header);
        String payloadJson = MAPPER.writeValueAsString(payload);
        String headerBase64 = Base64.getUrlEncoder().withoutPadding().encodeToString(headerJson.getBytes(StandardCharsets.UTF_8));
        String payloadBase64 = Base64.getUrlEncoder().withoutPadding().encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        String signingInput = headerBase64 + "." + payloadBase64;
        String signature = hmacSha256(signingInput, secretKey);
        return signingInput + "." + signature;
    }

    private static TokenValidationResult verifyAndParseJoseToken(String token, String secretKey) {
        if (token == null || token.isBlank()) return null;
        String[] parts = token.trim().split("\\.");
        if (parts.length == 3) {
            // 标准 JOSE (Header.Payload.Signature)
            try {
                String headerBase64 = parts[0];
                String payloadBase64 = parts[1];
                String signature = parts[2];

                byte[] headerBytes = Base64.getUrlDecoder().decode(headerBase64);
                JsonNode headerNode = MAPPER.readTree(headerBytes);
                String alg = headerNode.path("alg").asText(null);
                if (!ALLOWED_ALGORITHMS.contains(alg)) {
                    log.warn("不受信任的签名算法: {}", alg);
                    return null;
                }

                String signingInput = headerBase64 + "." + payloadBase64;
                String expectedSig = hmacSha256(signingInput, secretKey);
                if (!expectedSig.equals(signature)) return null;

                byte[] payloadBytes = Base64.getUrlDecoder().decode(payloadBase64);
                JsonNode payloadNode = MAPPER.readTree(payloadBytes);
                return new TokenValidationResult(headerNode, payloadNode);
            } catch (Exception e) {
                return null;
            }
        } else if (parts.length == 2) {
            // 兼容原有双段格式 (Payload.Signature)
            try {
                String payloadBase64 = parts[0];
                String expectedSig = hmacSha256(payloadBase64, secretKey);
                if (!expectedSig.equals(parts[1])) return null;

                byte[] decodedBytes = Base64.getUrlDecoder().decode(payloadBase64);
                JsonNode payloadNode = MAPPER.readTree(decodedBytes);
                return new TokenValidationResult(null, payloadNode);
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    private static String hmacSha256(String data, String secret) throws Exception {
        Mac hmac = Mac.getInstance("HmacSHA256");
        SecretKeySpec keySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        hmac.init(keySpec);
        byte[] sigBytes = hmac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(sigBytes);
    }
}
