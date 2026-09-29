package com.example.user.service;

import api.risk.RiskCommandDTO;
import com.example.user.entity.MerchantAudit;
import com.example.user.mapper.MerchantAuditMapper;
import com.example.user.mapper.MerchantMapper;
import com.example.user.mapper.WithdrawRequestMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserRiskCommandHandlerTest {
    private MerchantMapper merchantMapper;
    private MerchantAuditMapper auditMapper;
    private UserRiskCommandHandler handler;

    @BeforeEach
    void setUp() {
        merchantMapper = mock(MerchantMapper.class);
        auditMapper = mock(MerchantAuditMapper.class);
        WithdrawRequestMapper withdrawMapper = mock(WithdrawRequestMapper.class);
        WithdrawService withdrawService = mock(WithdrawService.class);
        handler = new UserRiskCommandHandler(merchantMapper, auditMapper, withdrawMapper,
                withdrawService);
    }

    @Test
    void merchantFreezeWritesAuditOnlyWhenStateChanges() {
        RiskCommandDTO command = command();
        when(merchantMapper.freezeByRisk(eq(9L), eq("HIGH"), eq("DECISION1"), eq("高风险商家"),
                any(LocalDateTime.class))).thenReturn(1, 0);

        Assertions.assertTrue(handler.handle(command));
        Assertions.assertFalse(handler.handle(command));

        verify(merchantMapper, times(2)).freezeByRisk(eq(9L), eq("HIGH"), eq("DECISION1"),
                eq("高风险商家"), any(LocalDateTime.class));
        ArgumentCaptor<MerchantAudit> auditCaptor = ArgumentCaptor.forClass(MerchantAudit.class);
        verify(auditMapper, times(1)).insert(auditCaptor.capture());
        MerchantAudit audit = auditCaptor.getValue();
        Assertions.assertEquals(9L, audit.getMerchantId());
        Assertions.assertEquals("RISK_FREEZE", audit.getAction());
        Assertions.assertEquals(1L, audit.getAuditorId());
        Assertions.assertEquals("高风险商家", audit.getReason());
    }

    private RiskCommandDTO command() {
        return RiskCommandDTO.builder()
                .commandNo("CMD1")
                .scene("MERCHANT")
                .merchantId(9L)
                .command("FREEZE")
                .decisionNo("DECISION1")
                .reason("高风险商家")
                .operatorId(1L)
                .build();
    }
}
