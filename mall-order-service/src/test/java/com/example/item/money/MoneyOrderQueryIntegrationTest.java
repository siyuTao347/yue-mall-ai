package com.example.item.money;

import api.agent.money.*;
import api.exception.ForbiddenException;
import com.example.item.entity.PaymentOrder;
import com.example.item.entity.TradeOrder;
import com.example.item.entity.TradeOrchestrationTask;
import com.example.item.mapper.PaymentOrderMapper;
import com.example.item.mapper.TradeOrderMapper;
import com.example.item.mapper.TradeOrchestrationTaskMapper;
import com.example.item.payment.channel.ChannelKey;
import com.example.item.payment.channel.ChannelPaymentCommands.*;
import com.example.item.payment.channel.ChannelProfileSnapshot;
import com.example.item.payment.channel.PaymentChannelStrategy;
import com.example.item.payment.channel.PaymentStrategyRegistry;
import com.example.item.payment.domain.PaymentAttempt;
import com.example.item.payment.domain.PaymentFinancialEvent;
import com.example.item.payment.domain.PaymentMethod;
import com.example.item.payment.domain.PaymentRefundOrder;
import com.example.item.payment.mapper.PaymentAttemptMapper;
import com.example.item.payment.mapper.PaymentFinancialEventMapper;
import com.example.item.payment.mapper.PaymentRefundOrderMapper;
import com.example.item.reconciliation.domain.*;
import com.example.item.reconciliation.mapper.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MoneyOrderQueryIntegrationTest {

    private TradeOrderMapper tradeOrderMapper;
    private PaymentOrderMapper paymentOrderMapper;
    private PaymentAttemptMapper attemptMapper;
    private PaymentRefundOrderMapper refundMapper;
    private PaymentFinancialEventMapper financialEventMapper;
    private TradeOrchestrationTaskMapper orchestrationMapper;
    private ReconDiffMapper diffMapper;
    private ReconMatchMapper matchMapper;
    private ReconLocalItemMapper localItemMapper;
    private ReconChannelItemMapper channelItemMapper;
    private ReconRunMapper runMapper;
    private ReconTaskMapper taskMapper;
    private ReconFileMapper fileMapper;
    private MoneyChannelObservationMapper observationMapper;
    private PaymentStrategyRegistry strategyRegistry;
    private PaymentChannelStrategy mockStrategy;
    private ObjectMapper objectMapper;

    private MoneySubjectResolver subjectResolver;
    private MoneyChannelObservationService channelObservationService;
    private MoneyReconEvidenceService reconEvidenceService;
    private MoneyOrderQueryDubboServiceImpl moneyOrderQueryService;

    @BeforeEach
    void setUp() {
        tradeOrderMapper = mock(TradeOrderMapper.class);
        paymentOrderMapper = mock(PaymentOrderMapper.class);
        attemptMapper = mock(PaymentAttemptMapper.class);
        refundMapper = mock(PaymentRefundOrderMapper.class);
        financialEventMapper = mock(PaymentFinancialEventMapper.class);
        orchestrationMapper = mock(TradeOrchestrationTaskMapper.class);
        diffMapper = mock(ReconDiffMapper.class);
        matchMapper = mock(ReconMatchMapper.class);
        localItemMapper = mock(ReconLocalItemMapper.class);
        channelItemMapper = mock(ReconChannelItemMapper.class);
        runMapper = mock(ReconRunMapper.class);
        taskMapper = mock(ReconTaskMapper.class);
        fileMapper = mock(ReconFileMapper.class);
        observationMapper = mock(MoneyChannelObservationMapper.class);
        mockStrategy = mock(PaymentChannelStrategy.class);
        when(mockStrategy.paymentMethod()).thenReturn(PaymentMethod.WECHAT_PAY);
        when(mockStrategy.key()).thenReturn(ChannelKey.ofMock("WECHAT_PAY", "NATIVE"));
        strategyRegistry = new PaymentStrategyRegistry(List.of(mockStrategy));
        objectMapper = new ObjectMapper().findAndRegisterModules();

        subjectResolver = new MoneySubjectResolver(tradeOrderMapper, attemptMapper, refundMapper, diffMapper);
        channelObservationService = new MoneyChannelObservationService(attemptMapper, refundMapper, strategyRegistry, observationMapper, objectMapper);
        reconEvidenceService = new MoneyReconEvidenceService(diffMapper, runMapper, matchMapper, localItemMapper, channelItemMapper, taskMapper, fileMapper);

        moneyOrderQueryService = new MoneyOrderQueryDubboServiceImpl(
                subjectResolver,
                channelObservationService,
                reconEvidenceService,
                tradeOrderMapper,
                paymentOrderMapper,
                attemptMapper,
                refundMapper,
                financialEventMapper,
                orchestrationMapper,
                diffMapper
        );
    }

    private MoneyAccessContext buildValidAccess(Long userId, String role, String action, String scopeKey) {
        String svcProof = MoneySecuritySupport.createServiceIdentityProof("mall-money-agent-service", "mall-order-service");
        String delProof = MoneySecuritySupport.createDelegationProof(
                userId, role, scopeKey, "mall-order-service", List.of(action), "GRANT_001");
        return new MoneyAccessContext(
                svcProof, delProof, null, action, "REQ_001", "TRACE_001",
                "mall-money-agent-service", userId, role
        );
    }

    @Test
    @DisplayName("R01验证：缺少凭据、服务身份或委托票据时统一拒绝访问")
    void testSecurityEntryRejection() {
        MoneySubjectSelector selector = MoneySubjectSelector.forOrder("T1001");
        // access = null
        ResolveMoneySubjectResult res1 = moneyOrderQueryService.resolveSubject(null, selector);
        assertFalse(res1.isSuccess());
        assertEquals(MoneyErrorCodes.MONEY_FORBIDDEN, res1.getStatus());

        // access 空票据
        MoneyAccessContext emptyAccess = new MoneyAccessContext(null, null, null, "RESOLVE_SUBJECT", "R1", "T1", "agent", 888L, "BUYER");
        ResolveMoneySubjectResult res2 = moneyOrderQueryService.resolveSubject(emptyAccess, selector);
        assertFalse(res2.isSuccess());
        assertEquals(MoneyErrorCodes.MONEY_FORBIDDEN, res2.getStatus());

        // 快照查询缺少凭据抛出异常
        assertThrows(ForbiddenException.class, () ->
                moneyOrderQueryService.getSnapshot(null, MoneySnapshotQuery.forScope("ORDER:T1001")));

        // 渠道观察缺少凭据抛出异常
        assertThrows(ForbiddenException.class, () ->
                moneyOrderQueryService.observeChannel(null, MoneyChannelQuery.forAttempt("ORDER:T1001", "PA_01", null)));

        // 买家自报买家但委托票据未授权该动作抛出异常
        MoneyAccessContext wrongActionAccess = buildValidAccess(888L, "BUYER", "RESOLVE_SUBJECT", "ORDER:T1001");
        assertThrows(ForbiddenException.class, () ->
                moneyOrderQueryService.getSnapshot(wrongActionAccess, MoneySnapshotQuery.forScope("ORDER:T1001")));
    }

    @Test
    @DisplayName("测试目标解析：按 attemptNo 反查订单并校验买家权限和签发范围证明")
    void testResolveSubjectByAttemptNo() {
        PaymentAttempt attempt = new PaymentAttempt();
        attempt.setAttemptNo("PA_T1001_01");
        attempt.setOrderNo("T1001");
        when(attemptMapper.selectOne(any())).thenReturn(attempt);

        TradeOrder order = new TradeOrder();
        order.setOrderNo("T1001");
        order.setBuyerId(888L);
        order.setSellerId(999L);
        when(tradeOrderMapper.selectOne(any())).thenReturn(order);

        when(attemptMapper.selectList(any())).thenReturn(List.of(attempt));
        when(refundMapper.selectList(any())).thenReturn(List.of());

        // 买家合法凭据
        MoneyAccessContext buyerAccess = buildValidAccess(888L, "BUYER", "RESOLVE_SUBJECT", "ORDER:T1001");
        ResolveMoneySubjectResult result = moneyOrderQueryService.resolveSubject(buyerAccess, MoneySubjectSelector.forAttempt("PA_T1001_01"));

        assertTrue(result.isSuccess());
        assertEquals("T1001", result.getCanonicalOrderNo());
        assertEquals("BUYER:888", result.getBuyerRef());
        assertEquals("BUYER_VIEW", result.getAllowedView());
        assertEquals(1, result.getVerifiedAttemptRefs().size());
        assertNotNull(result.getScopeProofRef());

        // 验证签发的范围证明能被合法解析
        MoneySecuritySupport.ScopeProofClaims claims = MoneySecuritySupport.verifyAndParseScopeProof(result.getScopeProofRef(), "mall-money-services");
        assertNotNull(claims);
        assertEquals("T1001", claims.getOrderNo());
        assertEquals(888L, claims.getBuyerId());

        // 买家越权场景（伪造他人 userId 但未获签名委托）
        MoneyAccessContext unauthorizedAccess = buildValidAccess(777L, "BUYER", "RESOLVE_SUBJECT", "ORDER:T1001");
        ResolveMoneySubjectResult failResult = moneyOrderQueryService.resolveSubject(unauthorizedAccess, MoneySubjectSelector.forAttempt("PA_T1001_01"));
        assertFalse(failResult.isSuccess());
        assertEquals(MoneyErrorCodes.MONEY_FORBIDDEN, failResult.getStatus());
    }

    @Test
    @DisplayName("R03验证：原 profile 缺失时返回 PROFILE_UNRESOLVED 并终止渠道调用")
    void testProfileUnresolvedWhenSnapshotMissing() {
        PaymentAttempt attempt = new PaymentAttempt();
        attempt.setAttemptNo("PA_T1001_01");
        attempt.setOrderNo("T1001");
        attempt.setPaymentMethod("WECHAT_PAY");
        attempt.setAmount(new BigDecimal("99.00"));
        attempt.setCurrency("CNY");
        attempt.setProfileSnapshot(null); // 无快照
        when(attemptMapper.selectOne(any())).thenReturn(attempt);

        MoneyAccessContext staffAccess = buildValidAccess(1L, "FINANCE", "OBSERVE_CHANNEL", "ORDER:T1001");
        MoneyChannelQuery query = MoneyChannelQuery.forAttempt("ORDER:T1001", "PA_T1001_01", null);

        MoneyChannelObservation obs = moneyOrderQueryService.observeChannel(staffAccess, query);

        assertNotNull(obs);
        assertEquals(MoneyErrorCodes.MONEY_PROFILE_UNRESOLVED, obs.getErrorMsg());
        // 关键断言：未调用渠道策略
        verify(mockStrategy, never()).queryPayment(any());
    }

    @Test
    @DisplayName("R04验证：显式区分存在、状态与匹配事实，缺状态为UNKNOWN，退款NOT_FOUND不可解释为存在")
    void testChannelObservationStatusAndMatchSemantics() throws Exception {
        PaymentAttempt attempt = new PaymentAttempt();
        attempt.setAttemptNo("PA_T1001_01");
        attempt.setOrderNo("T1001");
        attempt.setPaymentMethod("WECHAT_PAY");
        attempt.setAmount(new BigDecimal("99.00"));
        attempt.setCurrency("CNY");
        attempt.setChannelMerchantNo("MERCH_WX_001");
        attempt.setAmount(new BigDecimal("99.00"));
        attempt.setCurrency("CNY");
        String profileJson = "{\"profileId\":\"wx_native_v1\",\"version\":1,\"paymentMethod\":\"WECHAT_PAY\",\"providerCode\":\"WECHAT\",\"productCode\":\"NATIVE\",\"channelMerchantNo\":\"MERCH_WX_001\",\"accountId\":\"acc\",\"zoneId\":\"Asia/Shanghai\",\"cutoffTime\":\"23:00:00\",\"feeRate\":0.006,\"settlementDayBasis\":\"NATURAL\",\"settlementOffsetDays\":1,\"settlementTime\":\"10:00:00\",\"settlementCalendarId\":\"CN\",\"settlementTarget\":\"MERCHANT\",\"statementAvailableTime\":\"09:00:00\"}";
        attempt.setProfileSnapshot(profileJson);
        when(attemptMapper.selectOne(any())).thenReturn(attempt);

        // 渠道返回：exists=true 但 status=null, currency=null
        ChannelPaymentResult payResult = new ChannelPaymentResult(
                true, "WX_TRADE_12345", null, new BigDecimal("99.00"),
                BigDecimal.ZERO, null, Instant.now(), null, "{}"
        );
        when(mockStrategy.queryPayment(any())).thenReturn(payResult);

        MoneyAccessContext access = buildValidAccess(1L, "FINANCE", "OBSERVE_CHANNEL", "ORDER:T1001");
        MoneyChannelQuery query = MoneyChannelQuery.forAttempt("ORDER:T1001", "PA_T1001_01", null);

        MoneyChannelObservation obs = moneyOrderQueryService.observeChannel(access, query);

        assertNotNull(obs);
        assertTrue(obs.getExists());
        // R04: status 缺失时不默认 SUCCESS，为 UNKNOWN
        assertEquals("UNKNOWN", obs.getRawStatus());
        // R04: 响应未证明商户时保持未验证 (false)
        assertFalse(obs.getMerchantMatched());
        // 币种缺失不可标为匹配
        assertFalse(obs.getCurrencyMatched());
    }

    @Test
    @DisplayName("R06验证：单笔退款状态变化后快照事实指纹必须发生实质改变")
    void testFingerprintChangesWhenFactChanges() {
        TradeOrder tradeOrder = new TradeOrder();
        tradeOrder.setOrderNo("T1001");
        tradeOrder.setBuyerId(888L);
        when(tradeOrderMapper.selectOne(any())).thenReturn(tradeOrder);

        PaymentOrder paymentOrder = new PaymentOrder();
        paymentOrder.setPaymentNo("PAY_001");
        paymentOrder.setOrderNo("T1001");
        paymentOrder.setStatus("PARTIAL_REFUND");
        paymentOrder.setAmount(new BigDecimal("100.00"));
        paymentOrder.setVersion(1L);
        when(paymentOrderMapper.selectOne(any())).thenReturn(paymentOrder);

        PaymentRefundOrder refund1 = new PaymentRefundOrder();
        refund1.setId(1L);
        refund1.setRefundNo("RF_001");
        refund1.setOrderNo("T1001");
        refund1.setStatus("PROCESSING");
        refund1.setAmount(new BigDecimal("30.00"));
        refund1.setCurrency("CNY");
        refund1.setVersion(1L);

        when(refundMapper.selectList(any())).thenReturn(List.of(refund1));
        when(attemptMapper.selectList(any())).thenReturn(List.of());
        when(financialEventMapper.selectList(any())).thenReturn(List.of());
        when(orchestrationMapper.selectOne(any())).thenReturn(null);
        when(diffMapper.selectList(any())).thenReturn(List.of());

        MoneyAccessContext access = buildValidAccess(888L, "BUYER", "QUERY_ORDER_SNAPSHOT", "ORDER:T1001");
        MoneySnapshotQuery query = MoneySnapshotQuery.forScope("ORDER:T1001");

        MoneyOrderSnapshot snapshotA = moneyOrderQueryService.getSnapshot(access, query);
        String fpA = snapshotA.getSnapshotFingerprint();

        // 退款单状态变迁为 SUCCEEDED
        PaymentRefundOrder refund2 = new PaymentRefundOrder();
        refund2.setId(1L);
        refund2.setRefundNo("RF_001");
        refund2.setOrderNo("T1001");
        refund2.setStatus("SUCCEEDED"); // 变化
        refund2.setAmount(new BigDecimal("30.00"));
        refund2.setCurrency("CNY");
        refund2.setVersion(2L);
        when(refundMapper.selectList(any())).thenReturn(List.of(refund2));

        MoneyOrderSnapshot snapshotB = moneyOrderQueryService.getSnapshot(access, query);
        String fpB = snapshotB.getSnapshotFingerprint();

        // 关键断言：指纹必须变化！
        assertNotEquals(fpA, fpB);
    }

    @Test
    @DisplayName("R07验证：超出 limit 时返回 TRUNCATED 与 nextCursor，支持续读")
    void testCursorPaginationAndTruncation() {
        TradeOrder tradeOrder = new TradeOrder();
        tradeOrder.setOrderNo("T1001");
        tradeOrder.setBuyerId(888L);
        when(tradeOrderMapper.selectOne(any())).thenReturn(tradeOrder);

        // 模拟返回 3 笔（limit=2，多查1笔用于探测截断）
        List<PaymentAttempt> rawAttempts = new ArrayList<>();
        for (long i = 1; i <= 3; i++) {
            PaymentAttempt a = new PaymentAttempt();
            a.setId(i);
            a.setAttemptNo("PA_" + i);
            a.setOrderNo("T1001");
            a.setAmount(new BigDecimal("10.00"));
            a.setCurrency("CNY");
            a.setStatus("SUCCEEDED");
            rawAttempts.add(a);
        }
        when(attemptMapper.selectList(any())).thenReturn(rawAttempts);
        when(refundMapper.selectList(any())).thenReturn(List.of());
        when(financialEventMapper.selectList(any())).thenReturn(List.of());
        when(orchestrationMapper.selectOne(any())).thenReturn(null);
        when(diffMapper.selectList(any())).thenReturn(List.of());

        MoneyAccessContext access = buildValidAccess(888L, "BUYER", "QUERY_ORDER_SNAPSHOT", "ORDER:T1001");
        MoneySnapshotQuery query = new MoneySnapshotQuery("ORDER:T1001", null, null, 2, null);

        MoneyOrderSnapshot snapshot = moneyOrderQueryService.getSnapshot(access, query);

        assertEquals(2, snapshot.getAttempts().size());
        assertTrue(snapshot.getCoverage().isHasMore());
        assertFalse(snapshot.getCoverage().isComplete());
        assertNotNull(snapshot.getCoverage().getCursor());
        assertTrue(snapshot.getCoverage().getCursor().startsWith("CUR_V1:") || snapshot.getCoverage().getCursor().startsWith("CUR:"));
    }

    @Test
    @DisplayName("R10验证：对账取证从 Task/File 还原窗口并读取原始 itemType")
    void testReconEvidenceSourceAndClassification() {
        ReconDiff diff = new ReconDiff();
        diff.setDiffNo("DIFF_001");
        diff.setBizKey("REF_SUSPENSE_123");
        diff.setFirstSeenRunNo("RUN_01");
        when(diffMapper.selectOne(any())).thenReturn(diff);

        ReconRun run = new ReconRun();
        run.setRunNo("RUN_01");
        run.setTaskNo("TASK_01");
        run.setFileId(100L);
        run.setExecutionStatus("SUCCESS");
        when(runMapper.selectOne(any())).thenReturn(run);

        ReconTask task = new ReconTask();
        task.setTaskNo("TASK_01");
        task.setBusinessDate(LocalDate.of(2026, 10, 30));
        task.setWindowEnd(LocalDateTime.now().minusHours(2));
        when(taskMapper.selectOne(any())).thenReturn(task);

        ReconFile file = new ReconFile();
        file.setId(100L);
        file.setStatus("PARSED");
        when(fileMapper.selectById(100L)).thenReturn(file);

        ReconChannelItem channelItem = new ReconChannelItem();
        channelItem.setId(200L);
        channelItem.setItemType("REFUND"); // 渠道真实条目类型
        channelItem.setAmount(new BigDecimal("50.00"));
        channelItem.setStatus("SUCCESS");
        when(channelItemMapper.selectList(any())).thenReturn(List.of(channelItem));

        MoneyAccessContext access = buildValidAccess(1L, "FINANCE", "QUERY_RECON_EVIDENCE", "DIFF:DIFF_001");
        MoneyReconEvidence evidence = moneyOrderQueryService.getReconEvidence(access, MoneyReconQuery.forDiffNo("DIFF_001"));

        assertNotNull(evidence);
        assertEquals("REFUND", evidence.getResolvedItemType());
        assertEquals("2026-10-30", evidence.getBillDate());
        assertTrue(evidence.getWindowClosed());
    }

    @Test
    @DisplayName("R12验证：观察审计保存失败时不伪造 AUDIT:obsNo 凭据")
    void testAuditPersistenceFailureDoesNotForgeRef() throws Exception {
        PaymentAttempt attempt = new PaymentAttempt();
        attempt.setAttemptNo("PA_T1001_01");
        attempt.setOrderNo("T1001");
        attempt.setPaymentMethod("WECHAT_PAY");
        attempt.setAmount(new BigDecimal("99.00"));
        attempt.setCurrency("CNY");
        String profileJson = "{\"profileId\":\"wx_native_v1\",\"version\":1,\"paymentMethod\":\"WECHAT_PAY\",\"providerCode\":\"WECHAT\",\"productCode\":\"NATIVE\",\"channelMerchantNo\":\"MERCH_WX_001\",\"accountId\":\"acc\",\"zoneId\":\"Asia/Shanghai\",\"cutoffTime\":\"23:00:00\",\"feeRate\":0.006,\"settlementDayBasis\":\"NATURAL\",\"settlementOffsetDays\":1,\"settlementTime\":\"10:00:00\",\"settlementCalendarId\":\"CN\",\"settlementTarget\":\"MERCHANT\",\"statementAvailableTime\":\"09:00:00\"}";
        attempt.setProfileSnapshot(profileJson);
        when(attemptMapper.selectOne(any())).thenReturn(attempt);

        ChannelPaymentResult payResult = new ChannelPaymentResult(
                true, "WX_TRADE_12345", "SUCCESS", new BigDecimal("99.00"),
                BigDecimal.ZERO, "CNY", Instant.now(), null, "{}"
        );
        when(mockStrategy.queryPayment(any())).thenReturn(payResult);

        // 模拟审计保存失败（数据库抛出异常）
        doThrow(new RuntimeException("DB error")).when(observationMapper).insert(any(MoneyChannelObservationEntity.class));

        MoneyAccessContext access = buildValidAccess(1L, "FINANCE", "OBSERVE_CHANNEL", "ORDER:T1001");
        MoneyChannelObservation obs = moneyOrderQueryService.observeChannel(access, MoneyChannelQuery.forAttempt("ORDER:T1001", "PA_T1001_01", null));

        assertNotNull(obs);
        // 关键断言：审计落库失败不得发出伪造的 AUDIT: 引用
        assertNull(obs.getEvidenceRef());
        assertEquals("OBSERVATION_AUDIT_NOT_PERSISTED", obs.getErrorMsg());
    }

    @Test
    @DisplayName("多编号关联校验：同时传入 orderNo 与冲突的 attemptNo 必须拒绝并返回 SUBJECT_MISMATCH")
    void testMultiIdCorrelationMismatch() {
        PaymentAttempt attempt = new PaymentAttempt();
        attempt.setAttemptNo("PA_T9999_01");
        attempt.setOrderNo("T9999"); // 属于订单 T9999
        when(attemptMapper.selectOne(any())).thenReturn(attempt);

        // 选择器同时指定 orderNo=T1001 与 attemptNo=PA_T9999_01
        MoneySubjectSelector selector = new MoneySubjectSelector("ORDER:T1001", "T1001", "PA_T9999_01", null, null);
        MoneyAccessContext financeAccess = buildValidAccess(1L, "FINANCE", "RESOLVE_SUBJECT", "ORDER:T1001");
        ResolveMoneySubjectResult res = moneyOrderQueryService.resolveSubject(financeAccess, selector);

        assertFalse(res.isSuccess());
        assertEquals(MoneyErrorCodes.MONEY_SUBJECT_MISMATCH, res.getStatus());
    }

    @Test
    @DisplayName("客服坐席权限与视图限制：限定到买家主体且仅赋予 BUYER_VIEW，不可拥有全平台财务视图")
    void testCustomerStaffViewRestriction() {
        TradeOrder order = new TradeOrder();
        order.setOrderNo("T1001");
        order.setBuyerId(888L);
        order.setSellerId(999L);
        when(tradeOrderMapper.selectOne(any())).thenReturn(order);
        when(attemptMapper.selectList(any())).thenReturn(List.of());
        when(refundMapper.selectList(any())).thenReturn(List.of());

        // 1. 客服坐席代客 (userId=888L 买家)
        MoneyAccessContext staffAccessOk = buildValidAccess(888L, "CUSTOMER_STAFF", "RESOLVE_SUBJECT", "ORDER:T1001");
        ResolveMoneySubjectResult resOk = moneyOrderQueryService.resolveSubject(staffAccessOk, MoneySubjectSelector.forOrder("T1001"));
        assertTrue(resOk.isSuccess());
        assertEquals("BUYER_VIEW", resOk.getAllowedView()); // 必须是 BUYER_VIEW 而非 FINANCE_VIEW

        // 2. 客服坐席主体与买家不匹配时拒绝
        MoneyAccessContext staffAccessWrongBuyer = buildValidAccess(777L, "CUSTOMER_STAFF", "RESOLVE_SUBJECT", "ORDER:T1001");
        ResolveMoneySubjectResult resForbidden = moneyOrderQueryService.resolveSubject(staffAccessWrongBuyer, MoneySubjectSelector.forOrder("T1001"));
        assertFalse(resForbidden.isSuccess());
        assertEquals(MoneyErrorCodes.MONEY_FORBIDDEN, resForbidden.getStatus());
    }

    @Test
    @DisplayName("分批签发资金范围证明：校验引用归属并限制单批上限 50 个")
    void testIssueFundScopeProof() {
        TradeOrder order = new TradeOrder();
        order.setOrderNo("T1001");
        order.setBuyerId(888L);
        order.setSellerId(999L);
        when(tradeOrderMapper.selectOne(any())).thenReturn(order);

        PaymentAttempt attempt = new PaymentAttempt();
        attempt.setAttemptNo("PA_T1001_01");
        attempt.setOrderNo("T1001");
        when(attemptMapper.selectOne(any())).thenReturn(attempt);

        // 1. 成功签发单批范围证明
        MoneyAccessContext access = buildValidAccess(888L, "BUYER", "ISSUE_FUND_SCOPE_PROOF", "ORDER:T1001");
        IssueFundScopeProofRequest req = IssueFundScopeProofRequest.builder()
                .orderNo("T1001")
                .scopeKey("ORDER:T1001")
                .attemptRefs(List.of("PA_T1001_01"))
                .refundRefs(List.of())
                .grantRef("GRANT_001")
                .build();
        IssueFundScopeProofResult result = moneyOrderQueryService.issueFundScopeProof(access, req);
        assertTrue(result.isSuccess());
        assertNotNull(result.getScopeProofRef());

        // 验证签发的证明受众包含 mall-user-service
        MoneySecuritySupport.ScopeProofClaims claims = MoneySecuritySupport.verifyAndParseScopeProof(
                result.getScopeProofRef(), "mall-user-service");
        assertNotNull(claims);
        assertEquals("T1001", claims.getOrderNo());
        assertEquals("ORDER:T1001", claims.getScopeKey());

        // 2. 超出 50 个引用拒绝
        List<String> largeRefs = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            largeRefs.add("PA_FAKE_" + i);
        }
        IssueFundScopeProofRequest largeReq = IssueFundScopeProofRequest.builder()
                .orderNo("T1001")
                .attemptRefs(largeRefs)
                .build();
        IssueFundScopeProofResult largeResult = moneyOrderQueryService.issueFundScopeProof(access, largeReq);
        assertFalse(largeResult.isSuccess());
        assertEquals(MoneyErrorCodes.MONEY_PARAM_INVALID, largeResult.getErrorCode());
    }
}
