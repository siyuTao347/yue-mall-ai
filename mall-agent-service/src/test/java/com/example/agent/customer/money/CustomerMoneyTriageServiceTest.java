package com.example.agent.customer.money;

import api.agent.money.MoneyProblemFamily;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CustomerMoneyTriageServiceTest {

    private AgentMoneyBindingMapper bindingMapper;
    private CustomerMoneyTriageService triageService;

    @BeforeEach
    void setUp() {
        bindingMapper = mock(AgentMoneyBindingMapper.class);
        triageService = new CustomerMoneyTriageService(bindingMapper);
    }

    @Test
    void triage_escalationKeywords_returnsHandoff() {
        var result = triageService.triage(100L, "S1", "PAYMENT_ANOMALY", Map.of("orderNo", "T1001"), "我被盗号了要求赔偿！");
        assertEquals(CustomerMoneyTriageService.TriageDecision.HANDOFF, result.decision());
    }

    @Test
    void triage_missingScope_returnsAskScope() {
        var result = triageService.triage(100L, "S1", "PAYMENT_ANOMALY", Map.of(), "我扣款失败了");
        assertEquals(CustomerMoneyTriageService.TriageDecision.ASK_SCOPE, result.decision());
    }

    @Test
    void triage_existingActiveBinding_returnsAttachExisting() {
        AgentMoneyBindingEntity existing = AgentMoneyBindingEntity.builder()
                .caseNo("MC_EXISTING_01")
                .orderNo("T1001")
                .caseState("OPEN")
                .build();
        when(bindingMapper.selectOne(any())).thenReturn(existing);

        var result = triageService.triage(100L, "S1", "PAYMENT_ANOMALY", Map.of("orderNo", "T1001"), "订单未到账");
        assertEquals(CustomerMoneyTriageService.TriageDecision.ATTACH_EXISTING, result.decision());
        assertEquals("MC_EXISTING_01", result.existingCaseNo());
    }

    @Test
    void triage_excessPayment_identifiesProblemFamilyAndTargetRef() {
        when(bindingMapper.selectOne(any())).thenReturn(null);

        var result = triageService.triage(100L, "S1", "PAYMENT_ANOMALY",
                Map.of("orderNo", "T1001", "attemptNo", "PA_01"), "这笔订单重复扣了两次款");
        assertEquals(CustomerMoneyTriageService.TriageDecision.REGISTER_INVESTIGATION, result.decision());
        assertEquals(MoneyProblemFamily.EXCESS_PAYMENT, result.problemFamily());
        assertEquals("PA_01", result.targetRef(), "有明确 attempt 时优先使用 attemptNo 作为 targetRef");
    }

    @Test
    void triage_refundAnomaly_identifiesRefundStatusFamily() {
        when(bindingMapper.selectOne(any())).thenReturn(null);

        var result = triageService.triage(100L, "S1", "REFUND_ANOMALY",
                Map.of("orderNo", "T1001", "refundNo", "RF_01"), "退款一直没到账");
        assertEquals(CustomerMoneyTriageService.TriageDecision.REGISTER_INVESTIGATION, result.decision());
        assertEquals(MoneyProblemFamily.REFUND_STATUS, result.problemFamily());
        assertEquals("RF_01", result.targetRef());
    }
}

