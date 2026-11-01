package com.example.agent.customer.money;

import api.agent.money.contract.DelegatedRequest;
import api.agent.money.contract.MoneyTaskReceipt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CustomerMoneyBridgeServiceTest {

    private MoneyAgentClient moneyAgentClient;
    private AgentMoneyBindingMapper bindingMapper;
    private AgentMoneyDispatchMapper dispatchMapper;
    private CustomerMoneyBridgeService bridgeService;

    @BeforeEach
    void setUp() {
        moneyAgentClient = mock(MoneyAgentClient.class);
        bindingMapper = mock(AgentMoneyBindingMapper.class);
        dispatchMapper = mock(AgentMoneyDispatchMapper.class);
        bridgeService = new CustomerMoneyBridgeService(moneyAgentClient, bindingMapper, dispatchMapper);
    }

    @Test
    void handleMoneyIntent_missingOrderNo_promptsForOrderNo() {
        var resp = bridgeService.handleMoneyIntent("PAYMENT_ANOMALY", "S1", 100L, Map.of());
        assertTrue(resp.replyText().contains("请提供您需要核对资金异常的订单编号"));
        assertNull(resp.caseNo());
        verifyNoInteractions(moneyAgentClient);
    }

    @Test
    void handleMoneyIntent_existingActiveBinding_returnsStatus() {
        AgentMoneyBindingEntity existing = AgentMoneyBindingEntity.builder()
                .caseNo("MC_TEST_01")
                .taskNo("MT_TEST_01")
                .orderNo("T202610310001")
                .userId(100L)
                .caseState("OPEN")
                .build();
        when(bindingMapper.selectList(any())).thenReturn(List.of(existing));
        when(moneyAgentClient.getCase("MC_TEST_01")).thenReturn(
                new MoneyAgentClient.MoneyCaseResponse("MC_TEST_01", "OPEN", "VERIFIED_NORMAL", "RPT_01", "{}")
        );

        var resp = bridgeService.handleMoneyIntent("PAYMENT_ANOMALY", "S1", 100L, Map.of("orderNo", "T202610310001"));
        assertTrue(resp.replyText().contains("正在跟进您订单 T202610310001 的资金核查案件"));
        assertEquals("MC_TEST_01", resp.caseNo());
        verify(moneyAgentClient, never()).start(any(), any(), any(), any());
    }

    @Test
    void handleMoneyIntent_newInvestigation_dispatchesAndCreatesBinding() {
        when(bindingMapper.selectList(any())).thenReturn(List.of());
        MoneyTaskReceipt receipt = MoneyTaskReceipt.builder()
                .caseNo("MC_NEW_01")
                .taskNo("MT_NEW_01")
                .contextId("CTX_01")
                .projectionRevision(1L)
                .build();
        when(moneyAgentClient.start(any(DelegatedRequest.class))).thenReturn(receipt);

        var resp = bridgeService.handleMoneyIntent("PAYMENT_ANOMALY", "S1", 100L, Map.of("orderNo", "T202610310001"));
        assertTrue(resp.replyText().contains("已为您正式受理订单 T202610310001 的资金核实与调查请求"));
        assertEquals("MC_NEW_01", resp.caseNo());
        assertEquals("MT_NEW_01", resp.taskNo());

        // 验证写入了 dispatch
        verify(dispatchMapper).insert(any(AgentMoneyDispatchEntity.class));
        verify(dispatchMapper, atLeastOnce()).updateById(any(AgentMoneyDispatchEntity.class));

        // 验证写入了 binding
        ArgumentCaptor<AgentMoneyBindingEntity> bindingCaptor = ArgumentCaptor.forClass(AgentMoneyBindingEntity.class);
        verify(bindingMapper).insert(bindingCaptor.capture());
        assertEquals("MC_NEW_01", bindingCaptor.getValue().getCaseNo());
        assertEquals("T202610310001", bindingCaptor.getValue().getOrderNo());
    }

    @Test
    void handleMoneyIntent_remoteFailure_recordsRetryWaitDispatchAndPendingMessage() {
        when(bindingMapper.selectList(any())).thenReturn(List.of());
        when(moneyAgentClient.start(any(DelegatedRequest.class))).thenThrow(new RuntimeException("Connection refused"));

        var resp = bridgeService.handleMoneyIntent("PAYMENT_ANOMALY", "S1", 100L, Map.of("orderNo", "T202610310001"));
        assertTrue(resp.replyText().contains("已登记您的资金核实请求"));
        assertNull(resp.caseNo());

        // 验证写入并更新了 dispatch 为 RETRY_WAIT
        verify(dispatchMapper).insert(any(AgentMoneyDispatchEntity.class));
        ArgumentCaptor<AgentMoneyDispatchEntity> captor = ArgumentCaptor.forClass(AgentMoneyDispatchEntity.class);
        verify(dispatchMapper, atLeastOnce()).updateById(captor.capture());
        assertEquals("RETRY_WAIT", captor.getValue().getState());
    }

    @Test
    void queryCaseProgress_withCaseNo_returnsFriendlyOutcome() {
        AgentMoneyBindingEntity binding = AgentMoneyBindingEntity.builder()
                .caseNo("MC_TEST_99")
                .taskNo("MT_TEST_99")
                .orderNo("T99")
                .userId(100L)
                .caseState("OPEN")
                .build();
        when(bindingMapper.selectOne(any())).thenReturn(binding);
        when(moneyAgentClient.getCase("MC_TEST_99")).thenReturn(
                new MoneyAgentClient.MoneyCaseResponse("MC_TEST_99", "OPEN", "NEEDS_REVIEW", "RPT_99", "{}")
        );

        var resp = bridgeService.handleMoneyIntent("MONEY_CASE_PROGRESS", "S1", 100L, Map.of("moneyCaseNo", "MC_TEST_99"));
        assertTrue(resp.replyText().contains("MC_TEST_99"));
        assertTrue(resp.replyText().contains("等待财务专员人工复核确认"));
    }

    @Test
    void queryCaseProgress_caseAndOrderMismatch_returnsConflict() {
        AgentMoneyBindingEntity binding = AgentMoneyBindingEntity.builder()
                .caseNo("MC_TEST_99")
                .taskNo("MT_TEST_99")
                .orderNo("T99")
                .userId(100L)
                .caseState("OPEN")
                .build();
        when(bindingMapper.selectOne(any())).thenReturn(binding);

        var resp = bridgeService.handleMoneyIntent("MONEY_CASE_PROGRESS", "S1", 100L,
                Map.of("moneyCaseNo", "MC_TEST_99", "orderNo", "T100_OTHER"));
        assertTrue(resp.replyText().contains("不一致"));
        assertTrue(resp.replyText().contains("T99"));
    }

    @Test
    void queryCaseProgress_multipleBindingsForOrder_returnsCandidateOptions() {
        AgentMoneyBindingEntity b1 = AgentMoneyBindingEntity.builder().caseNo("MC_01").orderNo("T99").userId(100L).caseState("OPEN").build();
        AgentMoneyBindingEntity b2 = AgentMoneyBindingEntity.builder().caseNo("MC_02").orderNo("T99").userId(100L).caseState("NEED_REVIEW").build();
        when(bindingMapper.selectList(any())).thenReturn(List.of(b1, b2));

        var resp = bridgeService.handleMoneyIntent("MONEY_CASE_PROGRESS", "S1", 100L, Map.of("orderNo", "T99"));
        assertTrue(resp.replyText().contains("存在多笔资金核查案件"));
        assertTrue(resp.replyText().contains("MC_01"));
        assertTrue(resp.replyText().contains("MC_02"));
    }
}
