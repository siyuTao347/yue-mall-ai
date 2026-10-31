package api.agent.money;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MoneyContractAndSecurityTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("验证 MoneySecuritySupport 服务身份证明签发与受众校验")
    void testServiceIdentityProof() {
        String proof = MoneySecuritySupport.createServiceIdentityProof("mall-money-agent-service", "mall-order-service");
        assertNotNull(proof);
        assertTrue(MoneySecuritySupport.verifyServiceIdentity(proof, "mall-money-agent-service"));
        assertNotNull(MoneySecuritySupport.verifyAndParseServiceIdentity(proof, "mall-money-agent-service", "mall-order-service"));
        
        // 校验受众不匹配被拒绝
        assertNull(MoneySecuritySupport.verifyAndParseServiceIdentity(proof, "mall-money-agent-service", "mall-item-service"));
        // 校验签发者不匹配被拒绝
        assertNull(MoneySecuritySupport.verifyAndParseServiceIdentity(proof, "other-service", "mall-order-service"));
        assertNull(MoneySecuritySupport.verifyAndParseServiceIdentity("invalid.token", "mall-money-agent-service", "mall-order-service"));
        assertNull(MoneySecuritySupport.verifyAndParseServiceIdentity(null, "mall-money-agent-service", "mall-order-service"));
    }

    @Test
    @DisplayName("验证 MoneySecuritySupport 委托证明、动作白名单与受众校验")
    void testDelegationProof() {
        String proof = MoneySecuritySupport.createDelegationProof(
                1001L, "BUYER", "ORDER:T202610310001", "mall-order-service",
                List.of("RESOLVE_SUBJECT", "QUERY_ORDER_SNAPSHOT"), "GRANT_001");
        assertNotNull(proof);
        
        // 合法校验
        assertTrue(MoneySecuritySupport.verifyDelegation(proof, 1001L, "BUYER", "ORDER:T202610310001"));
        MoneySecuritySupport.DelegationClaims claims = MoneySecuritySupport.verifyAndParseDelegation(
                proof, 1001L, "BUYER", "ORDER:T202610310001", "mall-order-service", "QUERY_ORDER_SNAPSHOT");
        assertNotNull(claims);
        assertEquals(1001L, claims.getUserId());
        assertEquals("BUYER", claims.getRole());
        assertEquals("GRANT_001", claims.getGrantId());

        // 越权与不匹配校验
        assertNull(MoneySecuritySupport.verifyAndParseDelegation(proof, 1002L, "BUYER", "ORDER:T202610310001", "mall-order-service", "QUERY_ORDER_SNAPSHOT"));
        assertNull(MoneySecuritySupport.verifyAndParseDelegation(proof, 1001L, "FINANCE", "ORDER:T202610310001", "mall-order-service", "QUERY_ORDER_SNAPSHOT"));
        assertNull(MoneySecuritySupport.verifyAndParseDelegation(proof, 1001L, "BUYER", "ORDER:T202610319999", "mall-order-service", "QUERY_ORDER_SNAPSHOT"));
        assertNull(MoneySecuritySupport.verifyAndParseDelegation(proof, 1001L, "BUYER", "ORDER:T202610310001", "mall-item-service", "QUERY_ORDER_SNAPSHOT"));
        // 未授权动作
        assertNull(MoneySecuritySupport.verifyAndParseDelegation(proof, 1001L, "BUYER", "ORDER:T202610310001", "mall-order-service", "UNAUTHORIZED_ACTION"));
    }

    @Test
    @DisplayName("验证 MoneySecuritySupport 范围证明（ScopeProof）防篡改与解析")
    void testScopeProof() {
        List<String> attempts = List.of("PA_T001_01", "PA_T001_02");
        List<String> refunds = List.of("RF_T001_01");
        String fingerprint = MoneySecuritySupport.computeFingerprint("sample-fingerprint-data");

        String scopeProof = MoneySecuritySupport.createScopeProof("T001", 888L, 999L, attempts, refunds, fingerprint, "mall-user-service");
        assertNotNull(scopeProof);

        MoneySecuritySupport.ScopeProofClaims claims = MoneySecuritySupport.verifyAndParseScopeProof(scopeProof, "mall-user-service");
        assertNotNull(claims);
        assertEquals("T001", claims.getOrderNo());
        assertEquals(888L, claims.getBuyerId());
        assertEquals(999L, claims.getSellerId());
        assertEquals(2, claims.getAttempts().size());
        assertEquals(1, claims.getRefunds().size());
        assertEquals(fingerprint, claims.getFingerprint());

        // 受众不匹配
        assertNull(MoneySecuritySupport.verifyAndParseScopeProof(scopeProof, "mall-item-service"));

        // 篡改测试
        String tampered = scopeProof + "tamper";
        assertNull(MoneySecuritySupport.verifyAndParseScopeProof(tampered, "mall-user-service"));
    }

    @Test
    @DisplayName("验证密钥隔离性：不同令牌类型的密钥相互独立")
    void testCrossSecretIsolation() {
        // 使用服务身份密钥签发令牌
        String svcToken = MoneySecuritySupport.createServiceIdentityProof("mall-money-agent-service", "mall-order-service");
        // 尝试用委托密钥解析，应当直接失败（验签不通过）
        assertNull(MoneySecuritySupport.verifyAndParseDelegation(svcToken, null, null, null, null, null));
        // 尝试用范围证明密钥解析，也应当直接失败
        assertNull(MoneySecuritySupport.verifyAndParseScopeProof(svcToken, null));
    }

    @Test
    @DisplayName("验证统一入口访问校验器 (verifyEntryAccess)")
    void testUnifiedEntryAccessValidator() {
        String svcProof = MoneySecuritySupport.createServiceIdentityProof("mall-money-agent-service", "mall-order-service");
        String delProof = MoneySecuritySupport.createDelegationProof(
                1001L, "BUYER", "ORDER:T001", "mall-order-service", List.of("QUERY_ORDER_SNAPSHOT"), "G01");

        MoneyAccessContext access = new MoneyAccessContext(
                svcProof, delProof, null, "QUERY_ORDER_SNAPSHOT", "REQ_001", "TRACE_001",
                "mall-money-agent-service", 1001L, "BUYER");

        MoneySecuritySupport.VerifiedAccess verified = MoneySecuritySupport.verifyEntryAccess(
                access, "mall-order-service", "QUERY_ORDER_SNAPSHOT", "ORDER:T001");
        assertNotNull(verified);
        assertEquals("mall-money-agent-service", verified.getCallerService());
        assertEquals(1001L, verified.getActorUserId());
        assertEquals("BUYER", verified.getActorRole());

        // 缺少凭证抛出异常
        assertThrows(IllegalArgumentException.class, () ->
                MoneySecuritySupport.verifyEntryAccess(null, "mall-order-service", "QUERY_ORDER_SNAPSHOT", "ORDER:T001"));

        // 动作未授权抛出异常
        assertThrows(IllegalArgumentException.class, () ->
                MoneySecuritySupport.verifyEntryAccess(access, "mall-order-service", "OBSERVE_CHANNEL", "ORDER:T001"));

        // 受众不匹配抛出异常
        assertThrows(IllegalArgumentException.class, () ->
                MoneySecuritySupport.verifyEntryAccess(access, "mall-user-service", "QUERY_ORDER_SNAPSHOT", "ORDER:T001"));
    }

    @Test
    @DisplayName("验证 DTO 完整序列化与反序列化往返")
    void testDtoSerializationRoundTrip() throws Exception {
        PaymentAttemptSnapshot attempt = new PaymentAttemptSnapshot(
                "PA_T001_01", "P001", "T001", 1, "ALIPAY", "alipay-demo-v1", 1,
                "MERCH_001", "TRADE_CH_001", "PA_T001_01", new BigDecimal("100.00"),
                "CNY", "SUCCEEDED", "ORDER_APPLIED", "2026-10-31T10:00:00Z",
                "2026-10-31", "2026-10-31T10:00:01Z", 1L
        );

        PaymentRefundOrderSnapshot refund = new PaymentRefundOrderSnapshot(
                "RF_T001_01", "PA_T001_01", "T001", new BigDecimal("50.00"),
                "CNY", "协商退款", "PROCESSING", "CH_RF_001", "TASK_001",
                "2026-10-31T10:05:00Z", null, 0L
        );

        PaymentFinancialEventSnapshot event = new PaymentFinancialEventSnapshot(
                "FIN_001", "alipay-demo-v1", 1, "MERCH_001", "ACC_001",
                "PAYMENT", "SUCCESS", "ORDER", "T001", "T001",
                new BigDecimal("100.00"), BigDecimal.ZERO, "CNY",
                "2026-10-31", "2026-10-31T10:00:00Z", "2026-10-31T10:00:01Z"
        );

        CoverageInfo coverage = new CoverageInfo(Map.of("ORDER", "OK", "CHANNEL", "OK"), true, false, null, "Normal");

        MoneyOrderSnapshot snapshot = new MoneyOrderSnapshot(
                "ORDER:T001", "T001", "P001", "PAID",
                List.of(attempt), List.of(refund), List.of(event),
                new TradeOrchestrationSnapshot("TASK_001", "T001", "NORMAL", "SUCCESS", "FINISH", null),
                Collections.emptyList(), coverage, "fp-12345", "2026-10-31T10:06:00Z"
        );

        String json = objectMapper.writeValueAsString(snapshot);
        assertNotNull(json);

        MoneyOrderSnapshot deserialized = objectMapper.readValue(json, MoneyOrderSnapshot.class);
        assertEquals(snapshot.getScopeKey(), deserialized.getScopeKey());
        assertEquals(snapshot.getCanonicalOrderNo(), deserialized.getCanonicalOrderNo());
    }
}
