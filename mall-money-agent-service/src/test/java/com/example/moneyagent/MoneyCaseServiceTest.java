package com.example.moneyagent;

import api.agent.money.*;
import com.example.moneyagent.api.MoneyA2aEndpoint;
import com.example.moneyagent.application.MoneyCaseService;
import com.example.moneyagent.investigation.MoneyInvestigationGraph;
import com.example.moneyagent.persistence.entity.MoneyCaseEntity;
import com.example.moneyagent.persistence.entity.MoneyReportEntity;
import com.example.moneyagent.persistence.entity.MoneyTaskEntity;
import com.example.moneyagent.persistence.mapper.MoneyCaseMapper;
import com.example.moneyagent.persistence.mapper.MoneyReportMapper;
import com.example.moneyagent.persistence.mapper.MoneyTaskMapper;
import com.example.moneyagent.tools.OrderMoneyQueryClient;
import com.example.moneyagent.tools.UserFundQueryClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MoneyCaseServiceTest {

    private MoneyCaseMapper caseMapper;
    private MoneyTaskMapper taskMapper;
    private MoneyReportMapper reportMapper;
    private com.example.moneyagent.persistence.mapper.MoneyRequestMapper requestMapper;
    private com.example.moneyagent.persistence.mapper.MoneyProjectionMapper projectionMapper;
    private com.example.moneyagent.persistence.mapper.MoneyEventMapper eventMapper;
    private OrderMoneyQueryClient orderQueryClient;
    private UserFundQueryClient fundQueryClient;
    private ObjectMapper objectMapper;

    private MoneyInvestigationGraph investigationGraph;
    private MoneyCaseService caseService;
    private MoneyA2aEndpoint a2aEndpoint;

    @BeforeEach
    void setUp() {
        caseMapper = mock(MoneyCaseMapper.class);
        taskMapper = mock(MoneyTaskMapper.class);
        reportMapper = mock(MoneyReportMapper.class);
        requestMapper = mock(com.example.moneyagent.persistence.mapper.MoneyRequestMapper.class);
        projectionMapper = mock(com.example.moneyagent.persistence.mapper.MoneyProjectionMapper.class);
        eventMapper = mock(com.example.moneyagent.persistence.mapper.MoneyEventMapper.class);
        orderQueryClient = mock(OrderMoneyQueryClient.class);
        fundQueryClient = mock(UserFundQueryClient.class);
        objectMapper = new ObjectMapper();

        investigationGraph = new MoneyInvestigationGraph(
                orderQueryClient, fundQueryClient, caseMapper, taskMapper, reportMapper, objectMapper);
        caseService = new MoneyCaseService(caseMapper, taskMapper, reportMapper, requestMapper, projectionMapper, eventMapper, investigationGraph);
        a2aEndpoint = new MoneyA2aEndpoint(caseService);
    }

    @Test
    @DisplayName("测试案件创建、原子去重与复用逻辑")
    void testCreateOrAttachCaseDeduplication() {
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
        verify(caseMapper, times(1)).insert(any(MoneyCaseEntity.class));
        verify(taskMapper, times(1)).insert(any(MoneyTaskEntity.class));
        verify(reportMapper, times(1)).insert(any(MoneyReportEntity.class));

        // 第二次调用：命中已有活跃案，复用案件不重复创建
        MoneyCaseEntity existing = MoneyCaseEntity.builder()
                .caseNo(res1.caseEntity().getCaseNo())
                .state("OPEN")
                .outcome("VERIFIED_NORMAL")
                .currentTaskNo("TASK_01")
                .build();
        when(caseMapper.selectOne(any())).thenReturn(existing);

        MoneyCaseService.CaseExecutionResult res2 = caseService.createOrAttachCase(
                "ORDER:T1001", "PAYMENT_CONFIRMATION", "T1001", "CUSTOMER", 100L, "BUYER");

        assertFalse(res2.isNew());
        assertEquals(res1.caseEntity().getCaseNo(), res2.caseEntity().getCaseNo());
    }

    @Test
    @DisplayName("测试 A2A JSON-RPC 协议 endpoint: money.start 与 money.getCase")
    void testA2aJsonRpcEndpoint() {
        when(caseMapper.selectOne(any())).thenReturn(null);

        ResolveMoneySubjectResult resolveResult = new ResolveMoneySubjectResult(
                "T1001", "BUYER:100", "SELLER:200", "BUYER_VIEW",
                List.of("PA_01"), List.of(), "scope-proof-ref", "fp-1", "SUCCESS", null
        );
        when(orderQueryClient.resolveSubject(any(), any())).thenReturn(resolveResult);

        Map<String, Object> rpcReq = Map.of(
                "jsonrpc", "2.0",
                "id", "call-1",
                "method", "money.start",
                "params", Map.of("orderNo", "T1001", "problemFamily", "PAYMENT_ANOMALY", "userId", 100)
        );

        Map<String, Object> resp = a2aEndpoint.handleJsonRpc(rpcReq);
        assertEquals("2.0", resp.get("jsonrpc"));
        assertEquals("call-1", resp.get("id"));
        assertNotNull(resp.get("result"));

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) resp.get("result");
        assertNotNull(result.get("caseNo"));
        assertEquals("OPEN", result.get("caseState"));
    }
}

