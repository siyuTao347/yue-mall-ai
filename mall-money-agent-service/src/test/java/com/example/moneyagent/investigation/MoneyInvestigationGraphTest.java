package com.example.moneyagent.investigation;

import api.agent.money.*;
import com.example.moneyagent.application.MoneyGrantService;
import com.example.moneyagent.persistence.entity.MoneyCaseEntity;
import com.example.moneyagent.persistence.entity.MoneyEventEntity;
import com.example.moneyagent.persistence.entity.MoneyInputRequestEntity;
import com.example.moneyagent.persistence.entity.MoneyProjectionEntity;
import com.example.moneyagent.persistence.entity.MoneyReportEntity;
import com.example.moneyagent.persistence.entity.MoneyTaskEntity;
import com.example.moneyagent.persistence.mapper.*;
import com.example.moneyagent.tools.OrderMoneyQueryClient;
import com.example.moneyagent.tools.UserFundQueryClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MoneyInvestigationGraphTest {

    private OrderMoneyQueryClient orderQueryClient;
    private UserFundQueryClient fundQueryClient;
    private MoneyCaseMapper caseMapper;
    private MoneyTaskMapper taskMapper;
    private MoneyReportMapper reportMapper;
    private MoneyInputRequestMapper inputRequestMapper;
    private MoneyProjectionMapper projectionMapper;
    private MoneyEventMapper eventMapper;
    private MoneyGrantService grantService;
    private MockMoneyModelClient modelClient;
    private MoneyPlanValidator planValidator;
    private ObjectMapper objectMapper;

    private MoneyInvestigationGraph graph;

    @BeforeEach
    void setUp() {
        orderQueryClient = mock(OrderMoneyQueryClient.class);
        fundQueryClient = mock(UserFundQueryClient.class);
        caseMapper = mock(MoneyCaseMapper.class);
        taskMapper = mock(MoneyTaskMapper.class);
        reportMapper = mock(MoneyReportMapper.class);
        inputRequestMapper = mock(MoneyInputRequestMapper.class);
        projectionMapper = mock(MoneyProjectionMapper.class);
        eventMapper = mock(MoneyEventMapper.class);
        grantService = mock(MoneyGrantService.class);
        modelClient = new MockMoneyModelClient();
        planValidator = new MoneyPlanValidator();
        objectMapper = new ObjectMapper();

        graph = new MoneyInvestigationGraph(
                orderQueryClient, fundQueryClient, caseMapper, taskMapper, reportMapper,
                inputRequestMapper, projectionMapper, eventMapper, grantService,
                modelClient, planValidator, objectMapper);

        // 默认 Grant 有效
        when(grantService.isGrantActive(any())).thenReturn(true);
    }

    @Test
    @DisplayName("测试掉单场景分析：外部已支付但平台未支付 -> 判定 NEEDS_REVIEW，案件状态为 NEED_REVIEW")
    void testDropOrderInvestigationNeedsReview() {
        MoneyCaseEntity caseEntity = MoneyCaseEntity.builder()
                .caseNo("MC_DROP_ORDER_01")
                .scopeKey("ORDER:T1001")
                .targetRef("T1001")
                .problemFamily("PAYMENT_CONFIRMATION")
                .grantRef("GRANT_01")
                .state("OPEN")
                .build();

        MoneyTaskEntity taskEntity = MoneyTaskEntity.builder()
                .taskNo("MT_DROP_ORDER_01")
                .caseNo("MC_DROP_ORDER_01")
                .state("SUBMITTED")
                .version(1L)
                .build();

        // Mock 调查对象解析
        ResolveMoneySubjectResult resolveRes = new ResolveMoneySubjectResult(
                "T1001", "BUYER:100", "SELLER:200", "BUYER_VIEW",
                List.of("PA_01"), List.of(), "scope-proof-01", "fp-1", "SUCCESS", null);
        when(orderQueryClient.resolveSubject(any(), any())).thenReturn(resolveRes);

        // Mock 平台订单快照：未支付
        MoneyOrderSnapshot orderSnapshot = new MoneyOrderSnapshot(
                "ORDER:T1001", "T1001", "PAY_01", "INIT",
                List.of(), List.of(), List.of(), null, List.of(),
                new CoverageInfo(Map.of("ORDER", "OK"), true, false, null, "Normal"),
                "fp-snap", "2026-10-31T10:00:00Z");
        when(orderQueryClient.getSnapshot(any(), any())).thenReturn(orderSnapshot);

        // Mock 渠道观察：外部实际扣款成功 SUCCESS
        MoneyChannelObservation channelObs = new MoneyChannelObservation(
                "OBS_1", "ORDER:T1001", "PA_01", null, "SUCCESS", true, "SUCCESS",
                new BigDecimal("99.00"), "CNY", "MERCH_1", "TRADE_1", null, null, null,
                "MOCK", true, true, true, "AUDIT_1", null);
        when(orderQueryClient.observeChannel(any(), any())).thenReturn(channelObs);

        MoneyReportEntity report = graph.executeInvestigation(caseEntity, taskEntity);

        assertNotNull(report);
        assertEquals("NEEDS_REVIEW", report.getOutcome());
        assertEquals("NEED_REVIEW", caseEntity.getState());
        assertEquals("COMPLETED", taskEntity.getState());
        verify(reportMapper, times(1)).insert(any(MoneyReportEntity.class));
        verify(projectionMapper, atLeastOnce()).insert(any(MoneyProjectionEntity.class));
        verify(eventMapper, times(1)).insert(any(MoneyEventEntity.class));
    }

    @Test
    @DisplayName("测试正常未支付场景：外部无扣款记录 -> 判定 VERIFIED_NORMAL，案件解决并释放活动去重键")
    void testNormalUnpaidInvestigationVerifiedNormal() {
        MoneyCaseEntity caseEntity = MoneyCaseEntity.builder()
                .caseNo("MC_NORMAL_01")
                .scopeKey("ORDER:T1002")
                .targetRef("T1002")
                .problemFamily("PAYMENT_CONFIRMATION")
                .grantRef("GRANT_01")
                .state("OPEN")
                .activeDedupeKey("DEDUPE_KEY_123")
                .build();

        MoneyTaskEntity taskEntity = MoneyTaskEntity.builder()
                .taskNo("MT_NORMAL_01")
                .caseNo("MC_NORMAL_01")
                .state("SUBMITTED")
                .version(1L)
                .build();

        ResolveMoneySubjectResult resolveRes = new ResolveMoneySubjectResult(
                "T1002", "BUYER:100", "SELLER:200", "BUYER_VIEW",
                List.of("PA_02"), List.of(), "scope-proof-02", "fp-2", "SUCCESS", null);
        when(orderQueryClient.resolveSubject(any(), any())).thenReturn(resolveRes);

        MoneyOrderSnapshot orderSnapshot = new MoneyOrderSnapshot(
                "ORDER:T1002", "T1002", "PAY_02", "INIT",
                List.of(), List.of(), List.of(), null, List.of(), null, "fp-snap", "2026-10-31T10:00:00Z");
        when(orderQueryClient.getSnapshot(any(), any())).thenReturn(orderSnapshot);

        // 渠道无扣款记录
        MoneyChannelObservation channelObs = new MoneyChannelObservation(
                "OBS_2", "ORDER:T1002", "PA_02", null, "NOT_FOUND", false, "NOT_FOUND",
                BigDecimal.ZERO, "CNY", "MERCH_1", null, null, null, null,
                "MOCK", false, false, false, "AUDIT_2", null);
        when(orderQueryClient.observeChannel(any(), any())).thenReturn(channelObs);

        MoneyReportEntity report = graph.executeInvestigation(caseEntity, taskEntity);

        assertNotNull(report);
        assertEquals("VERIFIED_NORMAL", report.getOutcome());
        assertEquals("RESOLVED", caseEntity.getState());
        assertNull(caseEntity.getActiveDedupeKey(), "案件解决后活动去重键应被释放");
        assertNotNull(caseEntity.getClosedAt());
    }

    @Test
    @DisplayName("测试模型规划动作：触发 REQUEST_INPUT 节点挂起任务与案件状态切为 NEED_INPUT")
    void testModelPlanRequestsInputSuspendsExecution() {
        modelClient.setScenario("REQUEST_INPUT_SCENARIO");

        MoneyCaseEntity caseEntity = MoneyCaseEntity.builder()
                .caseNo("MC_REQ_INPUT_01")
                .scopeKey("ORDER:T1003")
                .targetRef("T1003")
                .problemFamily("PAYMENT_CONFIRMATION")
                .grantRef("GRANT_01")
                .state("OPEN")
                .build();

        MoneyTaskEntity taskEntity = MoneyTaskEntity.builder()
                .taskNo("MT_REQ_INPUT_01")
                .caseNo("MC_REQ_INPUT_01")
                .state("SUBMITTED")
                .version(1L)
                .build();

        ResolveMoneySubjectResult resolveRes = new ResolveMoneySubjectResult(
                "T1003", "BUYER:100", "SELLER:200", "BUYER_VIEW",
                List.of("PA_03"), List.of(), "scope-proof-03", "fp-3", "SUCCESS", null);
        when(orderQueryClient.resolveSubject(any(), any())).thenReturn(resolveRes);

        MoneyReportEntity report = graph.executeInvestigation(caseEntity, taskEntity);

        assertNull(report, "挂起等待补证时报告尚未产出，应返回 null");
        assertEquals("INPUT_REQUIRED", taskEntity.getState());
        assertEquals("USER_INPUT_REQUIRED", taskEntity.getWaitReason());
        assertEquals("NEED_INPUT", caseEntity.getState());
        verify(inputRequestMapper, times(1)).insert(any(MoneyInputRequestEntity.class));
    }

    @Test
    @DisplayName("测试授权失效时 VALIDATE_SCOPE 拦截并挂起 AUTH_REQUIRED")
    void testValidateScopeFailsWhenGrantInactive() {
        when(grantService.isGrantActive(any())).thenReturn(false);

        MoneyCaseEntity caseEntity = MoneyCaseEntity.builder()
                .caseNo("MC_AUTH_01")
                .scopeKey("ORDER:T1004")
                .targetRef("T1004")
                .problemFamily("PAYMENT_CONFIRMATION")
                .grantRef("GRANT_REVOKED")
                .state("OPEN")
                .build();

        MoneyTaskEntity taskEntity = MoneyTaskEntity.builder()
                .taskNo("MT_AUTH_01")
                .caseNo("MC_AUTH_01")
                .state("SUBMITTED")
                .version(1L)
                .build();

        MoneyReportEntity report = graph.executeInvestigation(caseEntity, taskEntity);

        assertNull(report);
        assertEquals("AUTH_REQUIRED", taskEntity.getState());
        assertEquals("GRANT_NOT_ACTIVE", taskEntity.getWaitReason());
        verify(orderQueryClient, never()).resolveSubject(any(), any());
    }

    @Test
    @DisplayName("测试发布报告前租约已超时过期触发保护中断")
    void testGuardAndPublishThrowsWhenLeaseExpired() {
        ResolveMoneySubjectResult resolveResult = new ResolveMoneySubjectResult(
                "T1005", "BUYER:100", "SELLER:200", "BUYER_VIEW",
                List.of("PA_01"), List.of(), "scope-proof-ref", "fp-1", "SUCCESS", null
        );
        when(orderQueryClient.resolveSubject(any(), any())).thenReturn(resolveResult);

        MoneyOrderSnapshot orderSnapshot = new MoneyOrderSnapshot(
                "ORDER:T1005", "T1005", "PAY_01", "PAID",
                List.of(), List.of(), List.of(), null, List.of(),
                new CoverageInfo(Map.of("ORDER", "OK"), true, false, null, "Normal"),
                "fp-order", "2026-10-31T10:00:00Z"
        );
        when(orderQueryClient.getSnapshot(any(), any())).thenReturn(orderSnapshot);

        MoneyCaseEntity caseEntity = MoneyCaseEntity.builder()
                .caseNo("MC_EXP_01")
                .scopeKey("ORDER:T1005")
                .targetRef("T1005")
                .problemFamily("PAYMENT_CONFIRMATION")
                .grantRef("GRANT_01")
                .state("OPEN")
                .build();

        MoneyTaskEntity taskEntity = MoneyTaskEntity.builder()
                .taskNo("MT_EXP_01")
                .caseNo("MC_EXP_01")
                .state("WORKING")
                .leaseOwner("WORKER_ORIGINAL")
                .fencingToken(1L)
                .version(1L)
                .build();

        // 模拟 DB 中租约已经超时
        MoneyTaskEntity dbTask = MoneyTaskEntity.builder()
                .taskNo("MT_EXP_01")
                .caseNo("MC_EXP_01")
                .leaseOwner("WORKER_ORIGINAL")
                .fencingToken(1L)
                .leaseUntil(LocalDateTime.now(ZoneOffset.UTC).minusSeconds(5))
                .build();
        when(taskMapper.selectOne(any())).thenReturn(dbTask);

        assertThrows(IllegalStateException.class, () -> graph.executeInvestigation(caseEntity, taskEntity));
        verify(reportMapper, never()).insert(any(MoneyReportEntity.class));
    }

    @Test
    @DisplayName("测试发布报告前 Fencing Token 不匹配被拒绝")
    void testGuardAndPublishThrowsWhenFencingTokenMismatch() {
        ResolveMoneySubjectResult resolveResult = new ResolveMoneySubjectResult(
                "T1006", "BUYER:100", "SELLER:200", "BUYER_VIEW",
                List.of("PA_01"), List.of(), "scope-proof-ref", "fp-1", "SUCCESS", null
        );
        when(orderQueryClient.resolveSubject(any(), any())).thenReturn(resolveResult);

        MoneyOrderSnapshot orderSnapshot = new MoneyOrderSnapshot(
                "ORDER:T1006", "T1006", "PAY_01", "PAID",
                List.of(), List.of(), List.of(), null, List.of(),
                new CoverageInfo(Map.of("ORDER", "OK"), true, false, null, "Normal"),
                "fp-order", "2026-10-31T10:00:00Z"
        );
        when(orderQueryClient.getSnapshot(any(), any())).thenReturn(orderSnapshot);

        MoneyCaseEntity caseEntity = MoneyCaseEntity.builder()
                .caseNo("MC_FENCE_01")
                .scopeKey("ORDER:T1006")
                .targetRef("T1006")
                .problemFamily("PAYMENT_CONFIRMATION")
                .grantRef("GRANT_01")
                .state("OPEN")
                .build();

        MoneyTaskEntity taskEntity = MoneyTaskEntity.builder()
                .taskNo("MT_FENCE_01")
                .caseNo("MC_FENCE_01")
                .state("WORKING")
                .leaseOwner("WORKER_1")
                .fencingToken(1L)
                .version(1L)
                .build();

        // 模拟 DB 中 fencing token 已经被其他 Worker 递增到 2
        MoneyTaskEntity dbTask = MoneyTaskEntity.builder()
                .taskNo("MT_FENCE_01")
                .caseNo("MC_FENCE_01")
                .leaseOwner("WORKER_1")
                .fencingToken(2L)
                .leaseUntil(LocalDateTime.now(ZoneOffset.UTC).plusSeconds(30))
                .build();
        when(taskMapper.selectOne(any())).thenReturn(dbTask);

        assertThrows(IllegalStateException.class, () -> graph.executeInvestigation(caseEntity, taskEntity));
        verify(reportMapper, never()).insert(any(MoneyReportEntity.class));
    }

    @Test
    @DisplayName("测试发布报告时存在初始投影则执行更新 (upsert)，不抛出唯一键冲突")
    void testUpsertProjectionUpdatesExistingInitialProjection() {
        ResolveMoneySubjectResult resolveResult = new ResolveMoneySubjectResult(
                "T1007", "BUYER:100", "SELLER:200", "BUYER_VIEW",
                List.of("PA_01"), List.of(), "scope-proof-ref", "fp-1", "SUCCESS", null
        );
        when(orderQueryClient.resolveSubject(any(), any())).thenReturn(resolveResult);

        MoneyOrderSnapshot orderSnapshot = new MoneyOrderSnapshot(
                "ORDER:T1007", "T1007", "PAY_01", "PAID",
                List.of(), List.of(), List.of(), null, List.of(),
                new CoverageInfo(Map.of("ORDER", "OK"), true, false, null, "Normal"),
                "fp-order", "2026-10-31T10:00:00Z"
        );
        when(orderQueryClient.getSnapshot(any(), any())).thenReturn(orderSnapshot);

        MoneyCaseEntity caseEntity = MoneyCaseEntity.builder()
                .caseNo("MC_UPSERT_01")
                .scopeKey("ORDER:T1007")
                .targetRef("T1007")
                .problemFamily("PAYMENT_CONFIRMATION")
                .grantRef("GRANT_01")
                .state("OPEN")
                .build();

        MoneyTaskEntity taskEntity = MoneyTaskEntity.builder()
                .taskNo("MT_UPSERT_01")
                .caseNo("MC_UPSERT_01")
                .state("WORKING")
                .leaseOwner("WORKER_1")
                .fencingToken(1L)
                .version(1L)
                .build();

        MoneyTaskEntity dbTask = MoneyTaskEntity.builder()
                .taskNo("MT_UPSERT_01")
                .caseNo("MC_UPSERT_01")
                .leaseOwner("WORKER_1")
                .fencingToken(1L)
                .leaseUntil(LocalDateTime.now(ZoneOffset.UTC).plusSeconds(30))
                .build();
        when(taskMapper.selectOne(any())).thenReturn(dbTask);

        // 模拟已有初始受理 BUYER 和 FINANCE 投影
        MoneyProjectionEntity initialProj = MoneyProjectionEntity.builder()
                .caseNo("MC_UPSERT_01")
                .audienceScope("BUYER")
                .revision(1L)
                .version(1L)
                .build();
        when(projectionMapper.selectOne(any())).thenReturn(initialProj);

        MoneyReportEntity report = graph.executeInvestigation(caseEntity, taskEntity);
        assertNotNull(report);

        // 验证调用了 updateById 进行更新，而不是 insert
        verify(projectionMapper, atLeastOnce()).updateById(any(MoneyProjectionEntity.class));
        verify(projectionMapper, never()).insert(any(MoneyProjectionEntity.class));
    }

    @Test
    @DisplayName("测试报告发布事务原子性：写 Outbox 失败触发事务回滚，Case 和 Task 不被提交至终态")
    void testPublishTransactionRollbackWhenOutboxFails() {
        MoneyOutboxMapper mockOutboxMapper = mock(MoneyOutboxMapper.class);
        org.springframework.transaction.PlatformTransactionManager txManager = mock(org.springframework.transaction.PlatformTransactionManager.class);
        org.springframework.transaction.TransactionStatus txStatus = mock(org.springframework.transaction.TransactionStatus.class);
        when(txManager.getTransaction(any())).thenReturn(txStatus);

        MoneyInvestigationGraph graphWithTx = new MoneyInvestigationGraph(
                orderQueryClient, fundQueryClient, caseMapper, taskMapper, reportMapper,
                inputRequestMapper, projectionMapper, eventMapper, grantService,
                modelClient, planValidator, objectMapper, null, null, mockOutboxMapper, null, null, txManager);

        MoneyCaseEntity caseEntity = MoneyCaseEntity.builder()
                .caseNo("MC_TX_FAIL_01")
                .scopeKey("ORDER:T_TX_01")
                .targetRef("T_TX_01")
                .problemFamily("PAYMENT_CONFIRMATION")
                .grantRef("GRANT_01")
                .state("OPEN")
                .build();

        MoneyTaskEntity taskEntity = MoneyTaskEntity.builder()
                .taskNo("MT_TX_FAIL_01")
                .caseNo("MC_TX_FAIL_01")
                .state("WORKING")
                .leaseOwner("WORKER_1")
                .fencingToken(1L)
                .version(1L)
                .checkpointJson("{\"nextNode\":\"GUARD_AND_PUBLISH\",\"ruleOutcome\":\"VERIFIED_NORMAL\"}")
                .build();

        MoneyTaskEntity dbTask = MoneyTaskEntity.builder()
                .taskNo("MT_TX_FAIL_01")
                .caseNo("MC_TX_FAIL_01")
                .leaseOwner("WORKER_1")
                .fencingToken(1L)
                .leaseUntil(LocalDateTime.now(ZoneOffset.UTC).plusSeconds(30))
                .build();
        when(taskMapper.selectOne(any())).thenReturn(dbTask);

        // 模拟 Outbox 写入抛出数据库异常
        doThrow(new RuntimeException("Outbox DB Connection Lost")).when(mockOutboxMapper).insert(any(com.example.moneyagent.persistence.entity.MoneyOutboxEntity.class));

        // 执行调查发布，必须抛出异常
        assertThrows(IllegalStateException.class, () -> graphWithTx.executeInvestigation(caseEntity, taskEntity));

        // 验证事务发生回滚且从未调用 commit
        verify(txManager).rollback(txStatus);
        verify(txManager, never()).commit(any());

        // 验证因为处于事务中且按设计顺序在 outbox 之后更新 case/task，caseMapper.updateById 从未被执行
        verify(caseMapper, never()).updateById(any(MoneyCaseEntity.class));
    }
}
