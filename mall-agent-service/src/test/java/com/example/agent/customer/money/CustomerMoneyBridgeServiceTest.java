package com.example.agent.customer.money;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
        when(bindingMapper.selectOne(any())).thenReturn(existing);
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
        when(bindingMapper.selectOne(any())).thenReturn(null);
        when(moneyAgentClient.start(eq("T202610310001"), eq("PAYMENT_CONFIRMATION"), eq("T202610310001"), eq(100L)))
                .thenReturn(new MoneyAgentClient.MoneyStartResponse("MC_NEW_01", "MT_NEW_01", "OPEN", "SUBMITTED", "UNDETERMINED", true));

        var resp = bridgeService.handleMoneyIntent("PAYMENT_ANOMALY", "S1", 100L, Map.of("orderNo", "T202610310001"));
        assertTrue(resp.replyText().contains("已为您正式受理订单 T202610310001 的资金核实与调查请求"));
        assertEquals("MC_NEW_01", resp.caseNo());
        assertEquals("MT_NEW_01", resp.taskNo());

        // 验证写入了 dispatch
        verify(dispatchMapper).insert(any(AgentMoneyDispatchEntity.class));
        verify(dispatchMapper).updateById(any(AgentMoneyDispatchEntity.class));

        // 验证写入了 binding
        ArgumentCaptor<AgentMoneyBindingEntity> bindingCaptor = ArgumentCaptor.forClass(AgentMoneyBindingEntity.class);
        verify(bindingMapper).insert(bindingCaptor.capture());
        assertEquals("MC_NEW_01", bindingCaptor.getValue().getCaseNo());
        assertEquals("T202610310001", bindingCaptor.getValue().getOrderNo());
    }

    @Test
    void handleMoneyIntent_remoteFailure_recordsFailedDispatchAndFallbackMessage() {
        when(bindingMapper.selectOne(any())).thenReturn(null);
        when(moneyAgentClient.start(any(), any(), any(), any())).thenThrow(new RuntimeException("Connection refused"));

        var resp = bridgeService.handleMoneyIntent("PAYMENT_ANOMALY", "S1", 100L, Map.of("orderNo", "T202610310001"));
        assertTrue(resp.replyText().contains("资金调查任务已登记到后台排队队列"));
        assertNull(resp.caseNo());

        // 验证写入并更新了 dispatch 为 FAILED
        verify(dispatchMapper).insert(any(AgentMoneyDispatchEntity.class));
        ArgumentCaptor<AgentMoneyDispatchEntity> captor = ArgumentCaptor.forClass(AgentMoneyDispatchEntity.class);
        verify(dispatchMapper).updateById(captor.capture());
        assertEquals("FAILED", captor.getValue().getState());
    }

    @Test
    void queryCaseProgress_withCaseNo_returnsFriendlyOutcome() {
        AgentMoneyBindingEntity binding = AgentMoneyBindingEntity.builder()
                .caseNo("MC_TEST_99")
                .taskNo("MT_TEST_99")
                .orderNo("T99")
                .userId(100L)
                .build();
        when(bindingMapper.selectOne(any())).thenReturn(binding);
        when(moneyAgentClient.getCase("MC_TEST_99")).thenReturn(
                new MoneyAgentClient.MoneyCaseResponse("MC_TEST_99", "OPEN", "NEEDS_REVIEW", "RPT_99", "{}")
        );

        var resp = bridgeService.handleMoneyIntent("MONEY_CASE_PROGRESS", "S1", 100L, Map.of("moneyCaseNo", "MC_TEST_99"));
        assertTrue(resp.replyText().contains("MC_TEST_99"));
        assertTrue(resp.replyText().contains("等待财务专员人工复核确认"));
    }
}
