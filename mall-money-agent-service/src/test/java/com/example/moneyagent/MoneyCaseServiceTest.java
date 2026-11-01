package com.example.moneyagent;

import api.agent.money.*;
import api.agent.money.contract.MoneyAgentException;
import com.example.moneyagent.persistence.entity.MoneyRequestEntity;
import com.example.moneyagent.application.MoneyCaseService;
import com.example.moneyagent.application.MoneyGrantService;
import com.example.moneyagent.application.MoneyTaskService;
import com.example.moneyagent.application.MoneyInputService;
import com.example.moneyagent.worker.MoneyTaskWorker;
import com.example.moneyagent.investigation.MockMoneyModelClient;
import com.example.moneyagent.investigation.MoneyPlanValidator;
import com.example.moneyagent.investigation.MoneyInvestigationGraph;
import com.example.moneyagent.persistence.entity.MoneyCaseEntity;
import com.example.moneyagent.persistence.entity.MoneyProjectionEntity;
import com.example.moneyagent.persistence.entity.MoneyReportEntity;
import com.example.moneyagent.persistence.entity.MoneyTaskEntity;
import com.example.moneyagent.persistence.mapper.*;
import com.example.moneyagent.tools.OrderMoneyQueryClient;
import com.example.moneyagent.tools.UserFundQueryClient;
import com.example.moneyagent.transport.a2a.MoneyA2aAdapter;
import com.example.moneyagent.transport.a2a.MoneyA2aEndpoint;
import com.example.moneyagent.transport.a2a.MoneySkillCatalog;
import com.example.moneyagent.transport.a2a.MoneyTaskStateMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MoneyCaseServiceTest {

    private MoneyCaseMapper caseMapper;
    private MoneyTaskMapper taskMapper;
    private MoneyReportMapper reportMapper;
    private MoneyRequestMapper requestMapper;
    private MoneyProjectionMapper projectionMapper;
    private MoneyEventMapper eventMapper;
    private MoneyGrantMapper grantMapper;
    private MoneyTaskAccessMapper taskAccessMapper;
    private OrderMoneyQueryClient orderQueryClient;
    private UserFundQueryClient fundQueryClient;
    private MoneyInputRequestMapper inputRequestMapper;
    private MoneyTaskInputMapper taskInputMapper;
    private ObjectMapper objectMapper;

    private MoneyGrantService grantService;
    private MoneyInvestigationGraph investigationGraph;
    private MoneyCaseService caseService;
    private MoneyTaskService taskService;
    private MoneyInputService inputService;
    private MoneyTaskWorker taskWorker;
    private MoneyA2aAdapter a2aAdapter;
    private MoneyA2aEndpoint a2aEndpoint;
    private MoneySkillCatalog skillCatalog;

    @BeforeEach
    void setUp() {
        caseMapper = mock(MoneyCaseMapper.class);
        taskMapper = mock(MoneyTaskMapper.class);
        reportMapper = mock(MoneyReportMapper.class);
        requestMapper = mock(MoneyRequestMapper.class);
        projectionMapper = mock(MoneyProjectionMapper.class);
        eventMapper = mock(MoneyEventMapper.class);
        grantMapper = mock(MoneyGrantMapper.class);
        taskAccessMapper = mock(MoneyTaskAccessMapper.class);
        inputRequestMapper = mock(MoneyInputRequestMapper.class);
        taskInputMapper = mock(MoneyTaskInputMapper.class);
        orderQueryClient = mock(OrderMoneyQueryClient.class);
        fundQueryClient = mock(UserFundQueryClient.class);
        objectMapper = new ObjectMapper();

        grantService = new MoneyGrantService(grantMapper, taskAccessMapper, taskMapper);
        MockMoneyModelClient modelClient = new MockMoneyModelClient();
        MoneyPlanValidator planValidator = new MoneyPlanValidator();
        investigationGraph = new MoneyInvestigationGraph(
                orderQueryClient, fundQueryClient, caseMapper, taskMapper, reportMapper,
                inputRequestMapper, projectionMapper, eventMapper, grantService,
                modelClient, planValidator, objectMapper);
        caseService = new MoneyCaseService(caseMapper, taskMapper, reportMapper, requestMapper, projectionMapper,
                eventMapper, grantService, investigationGraph);
        taskWorker = new MoneyTaskWorker(taskMapper, caseMapper, investigationGraph);
        taskService = new MoneyTaskService(taskMapper, caseMapper, taskAccessMapper, requestMapper);
        inputService = new MoneyInputService(taskMapper, caseMapper, taskInputMapper, inputRequestMapper, requestMapper, taskWorker);
        skillCatalog = new MoneySkillCatalog();
        a2aAdapter = new MoneyA2aAdapter(caseService, grantService, taskService, inputService, taskMapper, caseMapper,
                new MoneyTaskStateMapper(), objectMapper);
        a2aEndpoint = new MoneyA2aEndpoint(a2aAdapter, skillCatalog);
    }

    @Test
    @DisplayName("测试案件创建、原子去重与标准编号格式 (MC/MT+32位大写十六进制)")
    void testCreateOrAttachCaseDeduplicationAndIdFormat() {
        // 第一次调用：无已有案
        when(caseMapper.selectOne(any())).thenReturn(null);

        // Mock 调查工具调用
        ResolveMoneySubjectResult resolveResult = new ResolveMoneySubjectResult(
                "T1001", "BUYER:100", "SELLER:200", "BUYER_VIEW",
                List.of("PA_01"), List.of(), "scope-proof-ref", "fp-1", "SUCCESS", null
        );
        when(orderQueryClient.resolveSubject(any(), any())).thenReturn(resolveResult);

        MoneyOrderSnapshot orderSnapshot = new MoneyOrderSnapshot(
                "ORDER:T1001", "T1001", "PAY_01", "PAID",
                List.of(), List.of(), List.of(), null, List.of(),
                new CoverageInfo(Map.of("ORDER", "OK"), true, false, null, "Normal"),
                "fp-order", "2026-10-31T10:00:00Z"
        );
        when(orderQueryClient.getSnapshot(any(), any())).thenReturn(orderSnapshot);

        MoneyChannelObservation obs = new MoneyChannelObservation(
                "OBS_1", "ORDER:T1001", "PA_01", null, "SUCCESS", true, "SUCCESS",
                new BigDecimal("100.00"), "CNY", "MERCH_1", "TRADE_1", null, null, null,
                "MOCK", true, true, true, "AUDIT:OBS_1", null
        );
        when(orderQueryClient.observeChannel(any(), any())).thenReturn(obs);

        MoneyCaseService.CaseExecutionResult res1 = caseService.createOrAttachCase(
                "ORDER:T1001", "PAYMENT_CONFIRMATION", "T1001", "CUSTOMER", 100L, "BUYER");

        assertTrue(res1.isNew());
        assertNotNull(res1.caseEntity().getCaseNo());
        // 验证编号格式：以 MC 开头，总长度为 34 (MC + 32位大写十六进制)
        assertTrue(MoneyIdGenerator.isValidCaseNo(res1.caseEntity().getCaseNo()),
                "caseNo 必须为 MC + 32 位大写十六进制: " + res1.caseEntity().getCaseNo());
        assertTrue(MoneyIdGenerator.isValidTaskNo(res1.taskEntity().getTaskNo()),
                "taskNo 必须为 MT + 32 位大写十六进制: " + res1.taskEntity().getTaskNo());

        verify(caseMapper, times(1)).insert(any(MoneyCaseEntity.class));
        verify(taskMapper, times(1)).insert(any(MoneyTaskEntity.class));
        verify(projectionMapper, atLeastOnce()).insert(any(MoneyProjectionEntity.class));

        // 第二次调用：命中已有活跃案，复用案件不重复创建
        MoneyCaseEntity existing = MoneyCaseEntity.builder()
                .caseNo(res1.caseEntity().getCaseNo())
                .state("OPEN")
                .outcome("VERIFIED_NORMAL")
                .currentTaskNo("TASK_01")
                .grantRef("GRANT_01")
                .build();
        when(caseMapper.selectOne(any())).thenReturn(existing);

        MoneyCaseService.CaseExecutionResult res2 = caseService.createOrAttachCase(
                "ORDER:T1001", "PAYMENT_CONFIRMATION", "T1001", "CUSTOMER", 100L, "BUYER");

        assertFalse(res2.isNew());
        assertEquals(res1.caseEntity().getCaseNo(), res2.caseEntity().getCaseNo());
    }

    @Test
    @DisplayName("测试未知问题族枚举被严格拒绝，不静默回退")
    void testUnknownProblemFamilyRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                caseService.createOrAttachCase("ORDER:T1001", "UNKNOWN_FAMILY", "T1001", "CUSTOMER", 100L, "BUYER"));

        assertThrows(IllegalArgumentException.class, () ->
                caseService.createOrAttachCase("ORDER:T1001", "SETTLEMENT_DELAY", "T1001", "CUSTOMER", 100L, "BUYER"));
    }

    @Test
    @DisplayName("测试标准 A2A SendMessage 协议请求与回执 metadata")
    void testStandardA2aSendMessage() {
        when(caseMapper.selectOne(any())).thenReturn(null);

        ResolveMoneySubjectResult resolveResult = new ResolveMoneySubjectResult(
                "T1001", "BUYER:100", "SELLER:200", "BUYER_VIEW",
                List.of("PA_01"), List.of(), "scope-proof-ref", "fp-1", "SUCCESS", null
        );
        when(orderQueryClient.resolveSubject(any(), any())).thenReturn(resolveResult);

        Map<String, Object> invData = Map.of(
                "schema", "mall.money.investigation-request.v1",
                "requestId", "REQ-TEST-A2A-01",
                "problemFamily", "PAYMENT_CONFIRMATION",
                "subject", Map.of("scopeKey", "ORDER:T1001", "orderNo", "T1001")
        );

        Map<String, Object> rpcReq = Map.of(
                "jsonrpc", "2.0",
                "id", "call-1",
                "method", "SendMessage",
                "params", Map.of(
                        "message", Map.of(
                                "role", "user",
                                "parts", List.of(Map.of("kind", "data", "data", invData))
                        ),
                        "configuration", Map.of("returnImmediately", true, "historyLength", 0)
                )
        );

        String svcToken = MoneySecuritySupport.createServiceIdentityProof("mall-agent-service", "mall-money-services");
        String delToken = MoneySecuritySupport.createDelegationProof(100L, "BUYER", "ORDER:T1001", "mall-money-services", List.of("INVESTIGATE"), "GRANT_01");
        Map<String, Object> resp = a2aEndpoint.handleJsonRpc(rpcReq, "Bearer " + svcToken, delToken);
        assertEquals("2.0", resp.get("jsonrpc"));
        assertEquals("call-1", resp.get("id"));
        assertNotNull(resp.get("result"));

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) resp.get("result");
        assertNotNull(result.get("id"));
        assertTrue(List.of("submitted", "completed").contains(result.get("state")));

        @SuppressWarnings("unchecked")
        Map<String, Object> metadata = (Map<String, Object>) result.get("metadata");
        assertNotNull(metadata);
        assertNotNull(metadata.get("mall.money.receipt.v1"));
        assertNotNull(metadata.get("mall.money.view.v1"));
    }

    @Test
    @DisplayName("测试能力卡 /.well-known/agent-card.json 入口")
    void testAgentCardEndpoint() {
        Map<String, Object> card = a2aEndpoint.getAgentCard();
        assertNotNull(card);
        assertEquals("Money Error Agent", card.get("name"));
        assertEquals("1.0.0", card.get("version"));
        assertTrue(card.containsKey("skills"));
    }

    @Test
    @DisplayName("测试持久请求幂等：相同 requestId 重放回执，内容冲突抛出异常")
    void testPersistentRequestIdempotencyReplayAndConflict() {
        String requestId = "REQ-IDEMP-001";
        String scopeKey = "ORDER:T1001";
        String problemFamily = "PAYMENT_CONFIRMATION";
        String targetRef = "T1001";
        String origin = "CUSTOMER";
        Long userId = 100L;
        String role = "BUYER";

        String expectedHash = MoneySecuritySupport.computeFingerprint(
                "mall-agent-service:START:" + requestId + ":" + scopeKey + ":" + problemFamily + ":" + targetRef);

        // 1. 模拟数据库已存在相同 requestId 且 hash 匹配的记录 -> 重放回执
        MoneyRequestEntity existingReq = MoneyRequestEntity.builder()
                .callerId("mall-agent-service")
                .action("INVESTIGATE")
                .requestId(requestId)
                .requestHash(expectedHash)
                .caseNo("MC_EXISTING_01")
                .taskNo("MT_EXISTING_01")
                .state("COMPLETED")
                .build();
        when(requestMapper.selectOne(any())).thenReturn(existingReq);

        MoneyCaseEntity caseEntity = MoneyCaseEntity.builder().caseNo("MC_EXISTING_01").state("OPEN").build();
        MoneyTaskEntity taskEntity = MoneyTaskEntity.builder().taskNo("MT_EXISTING_01").state("COMPLETED").build();
        when(caseMapper.selectOne(any())).thenReturn(caseEntity);
        when(taskMapper.selectOne(any())).thenReturn(taskEntity);

        MoneyCaseService.CaseExecutionResult replayRes = caseService.createOrAttachCase(
                scopeKey, problemFamily, targetRef, origin, userId, role, "mall-agent-service", requestId, null);

        assertFalse(replayRes.isNew());
        assertEquals("MC_EXISTING_01", replayRes.caseEntity().getCaseNo());
        assertEquals("MT_EXISTING_01", replayRes.taskEntity().getTaskNo());

        // 2. 模拟数据库已存在相同 requestId 但 hash 不匹配 -> 抛出幂等冲突异常
        MoneyRequestEntity conflictReq = MoneyRequestEntity.builder()
                .callerId("mall-agent-service")
                .action("INVESTIGATE")
                .requestId(requestId)
                .requestHash("DIFFERENT_HASH")
                .caseNo("MC_EXISTING_01")
                .taskNo("MT_EXISTING_01")
                .state("COMPLETED")
                .build();
        when(requestMapper.selectOne(any())).thenReturn(conflictReq);

        MoneyAgentException ex = assertThrows(MoneyAgentException.class, () ->
                caseService.createOrAttachCase(scopeKey, problemFamily, targetRef, origin, userId, role, "mall-agent-service", requestId, null));
        assertEquals(MoneyErrorCodes.MONEY_IDEMPOTENCY_CONFLICT, ex.getCode());
        assertTrue(ex.getMessage().contains("幂等冲突"));
    }

    @Test
    @DisplayName("测试终态任务后的重分析：在同一案件下新建 Task 与报告版本，不直接返回陈旧终态")
    void testTerminalTaskReanalysisUnderExistingCase() {
        // Mock 调查工具调用
        ResolveMoneySubjectResult resolveResult = new ResolveMoneySubjectResult(
                "T1001", "BUYER:100", "SELLER:200", "BUYER_VIEW",
                List.of("PA_01"), List.of(), "scope-proof-ref", "fp-1", "SUCCESS", null
        );
        when(orderQueryClient.resolveSubject(any(), any())).thenReturn(resolveResult);

        // 模拟已有活跃案件，其当前任务已处于终态 COMPLETED
        MoneyCaseEntity existingCase = MoneyCaseEntity.builder()
                .caseNo("MC_EXISTING_CASE_01")
                .state("OPEN")
                .scopeKey("ORDER:T1001")
                .problemFamily("PAYMENT_CONFIRMATION")
                .targetRef("T1001")
                .currentTaskNo("MT_OLD_TERMINAL_TASK")
                .grantRef("GRANT_01")
                .version(1L)
                .build();

        MoneyTaskEntity oldTerminalTask = MoneyTaskEntity.builder()
                .taskNo("MT_OLD_TERMINAL_TASK")
                .caseNo("MC_EXISTING_CASE_01")
                .state("COMPLETED")
                .version(1L)
                .build();

        when(requestMapper.selectOne(any())).thenReturn(null);
        when(caseMapper.selectActiveByDedupeKeyForUpdate(any())).thenReturn(existingCase);
        when(caseMapper.selectOne(any())).thenReturn(existingCase);
        when(taskMapper.selectOne(any())).thenReturn(oldTerminalTask);

        MoneyCaseService.CaseExecutionResult result = caseService.createOrAttachCase(
                "ORDER:T1001", "PAYMENT_CONFIRMATION", "T1001", "CUSTOMER", 100L, "BUYER");

        assertFalse(result.isNew(), "应为同一案件下的重分析，isNew 应为 false");
        assertEquals("MC_EXISTING_CASE_01", result.caseEntity().getCaseNo());
        assertNotEquals("MT_OLD_TERMINAL_TASK", result.taskEntity().getTaskNo(), "重分析应生成新的 taskNo");
        assertTrue(MoneyIdGenerator.isValidTaskNo(result.taskEntity().getTaskNo()), "新 taskNo 格式正确");
        verify(taskMapper, atLeastOnce()).insert(any(MoneyTaskEntity.class));
        verify(caseMapper, atLeastOnce()).updateById(any(MoneyCaseEntity.class));
    }

    @Test
    @DisplayName("测试已结束案件的新异常建立关联 previousCaseNo")
    void testClosedCaseLinkingPreviousCaseNo() {
        // 无幂等记录
        when(requestMapper.selectOne(any())).thenReturn(null);
        // 无活跃案件
        when(caseMapper.selectActiveByDedupeKeyForUpdate(any())).thenReturn(null);

        // 存在历史已关闭/已解决案件 MC_CLOSED_HISTORIC_99
        MoneyCaseEntity historicClosedCase = MoneyCaseEntity.builder()
                .caseNo("MC_CLOSED_HISTORIC_99")
                .state("RESOLVED")
                .scopeKey("ORDER:T2002")
                .problemFamily("PAYMENT_CONFIRMATION")
                .build();

        // 模拟第一次 selectOne (查找 active 案件) 返回 null，第二次 selectOne (查找 closedCase) 返回 historicClosedCase
        when(caseMapper.selectOne(any())).thenReturn(null, historicClosedCase);

        ResolveMoneySubjectResult resolveResult = new ResolveMoneySubjectResult(
                "T2002", "BUYER:100", "SELLER:200", "BUYER_VIEW",
                List.of("PA_02"), List.of(), "scope-proof-ref", "fp-2", "SUCCESS", null
        );
        when(orderQueryClient.resolveSubject(any(), any())).thenReturn(resolveResult);

        MoneyCaseService.CaseExecutionResult res = caseService.createOrAttachCase(
                "ORDER:T2002", "PAYMENT_CONFIRMATION", "T2002", "CUSTOMER", 100L, "BUYER");

        assertTrue(res.isNew());
        assertEquals("MC_CLOSED_HISTORIC_99", res.caseEntity().getPreviousCaseNo(),
                "新案件应当继承关联已解决的 previousCaseNo");
    }
}
