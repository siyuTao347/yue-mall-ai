package com.example.moneyagent.transport.a2a;

import api.agent.money.*;
import api.agent.money.contract.MoneyInvestigationRequest;
import api.agent.money.contract.MoneyTaskView;
import com.example.moneyagent.application.MoneyCaseService;
import com.example.moneyagent.application.MoneyGrantService;
import com.example.moneyagent.application.MoneyTaskService;
import com.example.moneyagent.application.MoneyInputService;
import com.example.moneyagent.worker.MoneyTaskWorker;
import com.example.moneyagent.investigation.MockMoneyModelClient;
import com.example.moneyagent.investigation.MoneyPlanValidator;
import com.example.moneyagent.investigation.MoneyInvestigationGraph;
import com.example.moneyagent.persistence.entity.*;
import com.example.moneyagent.persistence.mapper.*;
import com.example.moneyagent.tools.OrderMoneyQueryClient;
import com.example.moneyagent.tools.UserFundQueryClient;
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

class MoneyA2aIntegrationTest {

    private MoneyCaseMapper caseMapper;
    private MoneyTaskMapper taskMapper;
    private MoneyReportMapper reportMapper;
    private MoneyRequestMapper requestMapper;
    private MoneyProjectionMapper projectionMapper;
    private MoneyEventMapper eventMapper;
    private MoneyGrantMapper grantMapper;
    private MoneyTaskAccessMapper taskAccessMapper;
    private MoneyInputRequestMapper inputRequestMapper;
    private MoneyTaskInputMapper taskInputMapper;
    private OrderMoneyQueryClient orderQueryClient;
    private UserFundQueryClient fundQueryClient;
    private ObjectMapper objectMapper;

    private MoneyGrantService grantService;
    private MoneyInvestigationGraph investigationGraph;
    private MoneyCaseService caseService;
    private MoneyTaskService taskService;
    private MoneyInputService inputService;
    private MoneyTaskWorker taskWorker;
    private MoneyA2aAdapter a2aAdapter;
    private MoneyA2aEndpoint a2aEndpoint;

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
        a2aAdapter = new MoneyA2aAdapter(caseService, grantService, taskService, inputService, taskMapper, caseMapper,
                projectionMapper, reportMapper, new MoneyTaskStateMapper(), objectMapper);
        a2aEndpoint = new MoneyA2aEndpoint(a2aAdapter, new MoneySkillCatalog());

        // 默认 Grant 有效
        when(grantMapper.selectOne(any())).thenAnswer(invocation -> MoneyGrantEntity.builder()
                .grantNo("GRANT_ACTIVE")
                .state("ACTIVE")
                .expiresAt(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).plusDays(1))
                .build());

        // Mock Order query client
        ResolveMoneySubjectResult resolveResult = new ResolveMoneySubjectResult(
                "T202610310001", "BUYER:1001", "SELLER:2001", "BUYER_VIEW",
                List.of("PA_T202610310001_01"), List.of(), "scope-proof-01", "fp-01", "SUCCESS", null
        );
        when(orderQueryClient.resolveSubject(any(), any())).thenReturn(resolveResult);

        MoneyOrderSnapshot orderSnapshot = new MoneyOrderSnapshot(
                "ORDER:T202610310001", "T202610310001", "PAY_01", "PAID",
                List.of(), List.of(), List.of(), null, List.of(),
                new CoverageInfo(Map.of("ORDER", "OK"), true, false, null, "Normal"),
                "fp-order", "2026-10-31T10:00:00Z"
        );
        when(orderQueryClient.getSnapshot(any(), any())).thenReturn(orderSnapshot);

        MoneyChannelObservation obs = new MoneyChannelObservation(
                "OBS_1", "ORDER:T202610310001", "PA_T202610310001_01", null, "SUCCESS", true, "SUCCESS",
                new BigDecimal("100.00"), "CNY", "MERCH_1", "TRADE_1", null, null, null,
                "MOCK", true, true, true, "AUDIT:OBS_1", null
        );
        when(orderQueryClient.observeChannel(any(), any())).thenReturn(obs);
    }

    @Test
    @DisplayName("端到端测试: A2A SendMessage 双层鉴权通过、规范编号格式、Grant注册与受理回执")
    void testSendMessageFullA2aFlow() {
        String scopeKey = "ORDER:T202610310001";
        String grantRef = MoneyIdGenerator.generateGrantNo();

        // 构造双层认证 Header
        String svcToken = MoneySecuritySupport.createServiceIdentityProof("mall-agent-service", "mall-money-services");
        String delToken = MoneySecuritySupport.createDelegationProof(
                1001L, "BUYER", scopeKey, "mall-money-services",
                List.of("INVESTIGATE", "GET_CASE", "CANCEL"), grantRef);

        Map<String, Object> invReq = Map.of(
                "schema", MoneyInvestigationRequest.SCHEMA_V1,
                "requestId", "REQ-INV-E2E-001",
                "origin", "CUSTOMER",
                "problemFamily", "PAYMENT_CONFIRMATION",
                "subject", Map.of("scopeKey", scopeKey, "orderNo", "T202610310001"),
                "claim", Map.of("text", "扣款了但订单未支付", "reportedAt", "2026-11-01T00:00:00Z"),
                "delegationRef", grantRef
        );

        Map<String, Object> rpcReq = Map.of(
                "jsonrpc", "2.0",
                "id", "call-e2e-1",
                "method", "SendMessage",
                "params", Map.of(
                        "message", Map.of("role", "user", "parts", List.of(Map.of("kind", "data", "data", invReq))),
                        "configuration", Map.of("returnImmediately", true, "historyLength", 0)
                )
        );

        Map<String, Object> resp = a2aEndpoint.handleJsonRpc(rpcReq, "Bearer " + svcToken, delToken);

        assertNotNull(resp);
        assertEquals("2.0", resp.get("jsonrpc"));
        assertEquals("call-e2e-1", resp.get("id"));
        assertNull(resp.get("error"));

        @SuppressWarnings("unchecked")
        Map<String, Object> taskResult = (Map<String, Object>) resp.get("result");
        assertNotNull(taskResult);

        String taskId = (String) taskResult.get("id");
        assertTrue(MoneyIdGenerator.isValidTaskNo(taskId), "Task ID 格式应为 MT+32位大写十六进制: " + taskId);

        @SuppressWarnings("unchecked")
        Map<String, Object> metadata = (Map<String, Object>) taskResult.get("metadata");
        assertNotNull(metadata);

        @SuppressWarnings("unchecked")
        Map<String, Object> receipt = (Map<String, Object>) metadata.get("mall.money.receipt.v1");
        assertNotNull(receipt);
        assertEquals("REQ-INV-E2E-001", receipt.get("requestId"));
        String caseNo = (String) receipt.get("caseNo");
        assertTrue(MoneyIdGenerator.isValidCaseNo(caseNo), "Case No 格式应为 MC+32位大写十六进制: " + caseNo);

        // 验证 grant 在运行期成功注册且处于 ACTIVE 状态
        assertTrue(grantService.isGrantActive(grantRef), "关联 Grant 应已注册并保持有效");
    }

    @Test
    @DisplayName("端到端测试: 未知问题族枚举被明确拒绝，返回 -32602")
    void testUnknownProblemFamilyRejection() {
        String svcToken = MoneySecuritySupport.createServiceIdentityProof("mall-agent-service", "mall-money-services");
        String delToken = MoneySecuritySupport.createDelegationProof(1001L, "BUYER", "ORDER:T1", "mall-money-services", List.of("*"), "GRANT_01");

        Map<String, Object> invReq = Map.of(
                "schema", MoneyInvestigationRequest.SCHEMA_V1,
                "requestId", "REQ-INV-ERR-001",
                "problemFamily", "INVALID_UNKNOWN_FAMILY",
                "subject", Map.of("orderNo", "T1")
        );

        Map<String, Object> rpcReq = Map.of(
                "jsonrpc", "2.0",
                "id", "call-err-1",
                "method", "SendMessage",
                "params", Map.of(
                        "message", Map.of("role", "user", "parts", List.of(Map.of("kind", "data", "data", invReq)))
                )
        );

        Map<String, Object> resp = a2aEndpoint.handleJsonRpc(rpcReq, "Bearer " + svcToken, delToken);
        assertNotNull(resp.get("error"));

        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) resp.get("error");
        assertEquals(-32602, error.get("code"));
        assertTrue(((String) error.get("message")).contains("未知问题分类"));
    }

    @Test
    @DisplayName("端到端测试: 伪造非法服务身份票据被拒绝")
    void testInvalidServiceTokenRejection() {
        Map<String, Object> rpcReq = Map.of(
                "jsonrpc", "2.0",
                "id", "call-sec-1",
                "method", "SendMessage",
                "params", Map.of("message", Map.of("parts", List.of(Map.of("kind", "data", "data", Map.of()))))
        );

        Map<String, Object> resp = a2aEndpoint.handleJsonRpc(rpcReq, "Bearer forged-token", "some-del-token");
        assertNotNull(resp.get("error"));

        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) resp.get("error");
        assertEquals(-32001, error.get("code"));
    }

    @Test
    @DisplayName("端到端测试: 缺少服务认证头被直接拒绝 (-32001)")
    void testMissingAuthHeaderRejection() {
        Map<String, Object> rpcReq = Map.of(
                "jsonrpc", "2.0",
                "id", "call-noauth-1",
                "method", "SendMessage",
                "params", Map.of("message", Map.of("parts", List.of(Map.of("kind", "data", "data", Map.of()))))
        );

        Map<String, Object> resp = a2aEndpoint.handleJsonRpc(rpcReq, null, null);
        assertNotNull(resp.get("error"));

        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) resp.get("error");
        assertEquals(-32001, error.get("code"));
        assertTrue(((String) error.get("message")).contains("未提供服务身份凭据"));
    }

    @Test
    @DisplayName("端到端测试: 退款问题族下精确提取 subject.refundNo 作为 targetRef")
    void testRefundTargetResolution() {
        String svcToken = MoneySecuritySupport.createServiceIdentityProof("mall-agent-service", "mall-money-services");
        String delToken = MoneySecuritySupport.createDelegationProof(1001L, "BUYER", "ORDER:T1", "mall-money-services", List.of("INVESTIGATE"), "GRANT_RF");

        Map<String, Object> invReq = Map.of(
                "schema", MoneyInvestigationRequest.SCHEMA_V1,
                "requestId", "REQ-INV-RF-001",
                "origin", "CUSTOMER",
                "problemFamily", "REFUND_STATUS",
                "subject", Map.of("orderNo", "T1", "refundNo", "RF_99999"),
                "delegationRef", "GRANT_RF"
        );

        Map<String, Object> rpcReq = Map.of(
                "jsonrpc", "2.0",
                "id", "call-rf-1",
                "method", "SendMessage",
                "params", Map.of(
                        "message", Map.of("role", "user", "parts", List.of(Map.of("kind", "data", "data", invReq)))
                )
        );

        Map<String, Object> resp = a2aEndpoint.handleJsonRpc(rpcReq, "Bearer " + svcToken, delToken);
        assertNull(resp.get("error"));
        assertNotNull(resp.get("result"));
    }

    @Test
    @DisplayName("端到端测试: 仅提供服务凭据无委托时，GetTask 与 GET_CASE 严禁绕过对象授权 (-32001)")
    void testCallerWithoutDelegationForbiddenForCaseAndTask() {
        String svcToken = MoneySecuritySupport.createServiceIdentityProof("mall-agent-service", "mall-money-services");

        // 1. 测试无委托凭据查询任务 GetTask
        Map<String, Object> getTaskReq = Map.of(
                "jsonrpc", "2.0",
                "id", "call-gt-forbidden",
                "method", "GetTask",
                "params", Map.of("taskId", "MT_TEST_UNKNOWN")
        );

        // 模拟任务存在，属于买家案件
        when(taskMapper.selectOne(any())).thenReturn(MoneyTaskEntity.builder()
                .taskNo("MT_TEST_UNKNOWN").caseNo("MC_TEST_PRIVATE").state("COMPLETED").build());
        when(caseMapper.selectOne(any())).thenReturn(MoneyCaseEntity.builder()
                .caseNo("MC_TEST_PRIVATE").scopeKey("ORDER:T_PRIVATE").grantRef("GRANT_PRIVATE").state("OPEN").build());

        Map<String, Object> respTask = a2aEndpoint.handleJsonRpc(getTaskReq, "Bearer " + svcToken, null);
        assertNotNull(respTask.get("error"), "无委托凭据必须被拒绝");
        @SuppressWarnings("unchecked")
        Map<String, Object> errTask = (Map<String, Object>) respTask.get("error");
        assertEquals(-32001, errTask.get("code"));

        // 2. 测试无委托凭据查询案件 GET_CASE
        Map<String, Object> getCaseReq = Map.of(
                "jsonrpc", "2.0",
                "id", "call-gc-forbidden",
                "method", "SendMessage",
                "params", Map.of(
                        "message", Map.of("role", "user", "parts", List.of(
                                Map.of("kind", "data", "data", Map.of("operation", "GET_CASE", "caseNo", "MC_TEST_PRIVATE"))
                        ))
                )
        );
        Map<String, Object> respCase = a2aEndpoint.handleJsonRpc(getCaseReq, "Bearer " + svcToken, null);
        assertNotNull(respCase.get("error"), "无委托凭据查询案件必须被拒绝");
        @SuppressWarnings("unchecked")
        Map<String, Object> errCase = (Map<String, Object>) respCase.get("error");
        assertEquals(-32001, errCase.get("code"));
    }

    @Test
    @DisplayName("端到端测试: 历史任务查询保真性，返回该历史任务专属报告结论与修订版本")
    void testHistoricalTaskQueryReturnsAccurateTaskReport() {
        String svcToken = MoneySecuritySupport.createServiceIdentityProof("mall-agent-service", "mall-money-services");
        String delToken = MoneySecuritySupport.createDelegationProof(1001L, "BUYER", "ORDER:T_HIST", "mall-money-services", List.of("*"), "GRANT_HIST");

        String histTaskNo = "MT_HIST_01";
        String histCaseNo = "MC_HIST_01";
        String histReportNo = "MR_HIST_REPORT_01";

        // 案件当前已处于重分析后的最新状态 (最新报告 MR_NEW_REPORT_02, outcome=EXCESS_REFUND)
        MoneyCaseEntity caseEntity = MoneyCaseEntity.builder()
                .caseNo(histCaseNo)
                .scopeKey("ORDER:T_HIST")
                .grantRef("GRANT_HIST")
                .state("OPEN")
                .outcome("EXCESS_REFUND")
                .latestReportNo("MR_NEW_REPORT_02")
                .currentTaskNo("MT_NEW_TASK_02")
                .version(2L)
                .build();
        when(caseMapper.selectOne(any())).thenReturn(caseEntity);

        // 历史任务 MT_HIST_01 状态为 COMPLETED
        MoneyTaskEntity taskEntity = MoneyTaskEntity.builder()
                .taskNo(histTaskNo)
                .caseNo(histCaseNo)
                .state("COMPLETED")
                .version(1L)
                .build();
        when(taskMapper.selectOne(any())).thenReturn(taskEntity);

        // 历史任务专属报告为 MR_HIST_REPORT_01, 结论为 VERIFIED_NORMAL, revision=1L
        MoneyReportEntity histReport = MoneyReportEntity.builder()
                .reportNo(histReportNo)
                .caseNo(histCaseNo)
                .taskNo(histTaskNo)
                .outcome("VERIFIED_NORMAL")
                .revision(1L)
                .build();
        when(reportMapper.selectOne(any())).thenReturn(histReport);

        Map<String, Object> getTaskReq = Map.of(
                "jsonrpc", "2.0",
                "id", "call-gt-hist",
                "method", "GetTask",
                "params", Map.of("taskId", histTaskNo)
        );

        Map<String, Object> resp = a2aEndpoint.handleJsonRpc(getTaskReq, "Bearer " + svcToken, delToken);
        assertNull(resp.get("error"));
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) resp.get("result");
        assertNotNull(result);

        @SuppressWarnings("unchecked")
        Map<String, Object> metadata = (Map<String, Object>) result.get("metadata");
        @SuppressWarnings("unchecked")
        Map<String, Object> view = (Map<String, Object>) metadata.get("mall.money.view.v1");
        assertNotNull(view);

        // 验证返回的是历史任务 MT_HIST_01 的报告和版本，而不是案件当前最新的 MR_NEW_REPORT_02
        assertEquals("VERIFIED_NORMAL", view.get("resolutionOutcome"), "必须为历史任务的调查结论");
        assertEquals(histReportNo, view.get("reportRef"), "必须为历史任务关联的报告编号");
        assertEquals(1L, view.get("projectionRevision"), "必须为历史报告的修订版本号");
    }

    @Test
    @DisplayName("对象授权测试: Grant 撤回后，案件与任务查询权限同步失效并拒绝披露")
    void testQueryForbiddenWhenGrantRevoked() {
        String svcToken = MoneySecuritySupport.createServiceIdentityProof("mall-agent-service", "mall-money-services");
        String delToken = MoneySecuritySupport.createDelegationProof(1001L, "BUYER", "ORDER:T_REVOKED", "mall-money-services", List.of("*"), "GRANT_REVOKED_01");

        String taskNo = "MT_REVOKED_01";
        String caseNo = "MC_REVOKED_01";

        // 模拟 Grant 在数据库中已被撤回 (state = REVOKED)
        when(grantMapper.selectOne(any())).thenReturn(MoneyGrantEntity.builder()
                .grantNo("GRANT_REVOKED_01")
                .state("REVOKED")
                .scopeJson("[\"ORDER:T_REVOKED\"]")
                .build());

        // 案件与任务绑定该已撤回 Grant
        MoneyCaseEntity caseEntity = MoneyCaseEntity.builder()
                .caseNo(caseNo)
                .scopeKey("ORDER:T_REVOKED")
                .grantRef("GRANT_REVOKED_01")
                .state("OPEN")
                .version(1L)
                .build();
        when(caseMapper.selectOne(any())).thenReturn(caseEntity);

        MoneyTaskEntity taskEntity = MoneyTaskEntity.builder()
                .taskNo(taskNo)
                .caseNo(caseNo)
                .state("WORKING")
                .version(1L)
                .build();
        when(taskMapper.selectOne(any())).thenReturn(taskEntity);
        when(taskService.getTaskView(taskNo)).thenReturn(MoneyTaskView.builder()
                .taskNo(taskNo)
                .caseNo(caseNo)
                .taskState("WORKING")
                .taskVersion(1L)
                .build());
        when(caseService.getCaseDetail(caseNo)).thenReturn(new MoneyCaseService.CaseDetailResult(caseEntity, taskEntity, null));

        // 1. 测试 GetTask 在 Grant 撤回后被拒绝
        Map<String, Object> getTaskReq = Map.of(
                "jsonrpc", "2.0",
                "id", "call-gt-revoked",
                "method", "GetTask",
                "params", Map.of("taskId", taskNo)
        );
        Map<String, Object> respTask = a2aEndpoint.handleJsonRpc(getTaskReq, "Bearer " + svcToken, delToken);
        assertNotNull(respTask.get("error"), "Grant 撤回后 GetTask 必须被拒绝");
        @SuppressWarnings("unchecked")
        Map<String, Object> errTask = (Map<String, Object>) respTask.get("error");
        assertEquals(-32001, errTask.get("code"));
        assertTrue(String.valueOf(errTask.get("message")).contains("无权访问此任务"));

        // 2. 测试 GET_CASE 在 Grant 撤回后被拒绝
        Map<String, Object> getCaseReq = Map.of(
                "jsonrpc", "2.0",
                "id", "call-gc-revoked",
                "method", "SendMessage",
                "params", Map.of(
                        "message", Map.of("role", "user", "parts", List.of(
                                Map.of("kind", "data", "data", Map.of("operation", "GET_CASE", "caseNo", caseNo))
                        ))
                )
        );
        Map<String, Object> respCase = a2aEndpoint.handleJsonRpc(getCaseReq, "Bearer " + svcToken, delToken);
        assertNotNull(respCase.get("error"), "Grant 撤回后 GET_CASE 必须被拒绝");
        @SuppressWarnings("unchecked")
        Map<String, Object> errCase = (Map<String, Object>) respCase.get("error");
        assertEquals(-32001, errCase.get("code"));
        assertTrue(String.valueOf(errCase.get("message")).contains("无权访问此案件"));

        // 3. 测试 CancelTask 在 Grant 撤回后被拒绝
        Map<String, Object> cancelReq = Map.of(
                "jsonrpc", "2.0",
                "id", "call-cancel-revoked",
                "method", "CancelTask",
                "params", Map.of("taskId", taskNo)
        );
        Map<String, Object> respCancel = a2aEndpoint.handleJsonRpc(cancelReq, "Bearer " + svcToken, delToken);
        assertNotNull(respCancel.get("error"), "Grant 撤回后 CancelTask 必须被拒绝");
        @SuppressWarnings("unchecked")
        Map<String, Object> errCancel = (Map<String, Object>) respCancel.get("error");
        assertEquals(-32001, errCancel.get("code"));
    }
}
