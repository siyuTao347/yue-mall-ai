package com.example.user.service;

import api.risk.RiskCommandDTO;
import api.risk.RiskDecisionResult;
import api.risk.RiskEvaluateRequest;
import api.risk.SensitiveWordScanner;
import api.trade.FundOperationResult;
import com.example.user.entity.Merchant;
import com.example.user.entity.WithdrawRequest;
import com.example.user.mapper.WithdrawRequestMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WithdrawServiceTest {
    private WithdrawRequestMapper withdrawMapper;
    private MerchantService merchantService;
    private FundService fundService;
    private RiskClient riskClient;
    private WithdrawService service;

    @BeforeEach
    void setUp() {
        withdrawMapper = mock(WithdrawRequestMapper.class);
        merchantService = mock(MerchantService.class);
        fundService = mock(FundService.class);
        riskClient = mock(RiskClient.class);
        when(riskClient.sensitiveWords()).thenReturn(SensitiveWordScanner.defaultWords());
        when(riskClient.evaluate(any(RiskEvaluateRequest.class))).thenReturn(pass());
        when(riskClient.identityHash(anyString())).thenReturn("withdraw-account-hash");
        when(merchantService.getApprovedMerchant(10L, 20L)).thenReturn(merchant());
        service = new WithdrawService(withdrawMapper, merchantService, fundService, riskClient);
    }

    @Test
    void applyUsesStableWithdrawNoForIdempotency() {
        when(withdrawMapper.selectOne(any())).thenReturn(null);
        when(fundService.withdrawFreeze("W20-20260928token", 20L, 10L,
                new BigDecimal("100.00"))).thenReturn(FundOperationResult.success("FUND1"));

        WithdrawRequest result = service.apply(10L, 20L, new BigDecimal("100.00"),
                "MOCK-ACCOUNT", "20260928token");

        Assertions.assertEquals("W20-20260928token", result.getWithdrawNo());
        ArgumentCaptor<WithdrawRequest> captor = ArgumentCaptor.forClass(WithdrawRequest.class);
        verify(withdrawMapper).insert(captor.capture());
        Assertions.assertEquals("W20-20260928token", captor.getValue().getWithdrawNo());
    }

    @Test
    void applyReturnsExistingRequestForSameClientToken() {
        WithdrawRequest existing = request();
        when(withdrawMapper.selectOne(any())).thenReturn(existing);

        WithdrawRequest result = service.apply(10L, 20L, new BigDecimal("100.00"),
                "MOCK-ACCOUNT", "20260928token");

        Assertions.assertSame(existing, result);
        verify(fundService, never()).withdrawFreeze(anyString(), any(), any(), any());
        verify(withdrawMapper, never()).insert(any(WithdrawRequest.class));
    }

    @Test
    void applyRejectsByRiskWithoutFreezingFunds() {
        when(withdrawMapper.selectOne(any())).thenReturn(null);
        when(riskClient.evaluate(any(RiskEvaluateRequest.class))).thenReturn(reject());

        WithdrawRequest result = service.apply(10L, 20L, new BigDecimal("100.00"),
                "MOCK-ACCOUNT", "20260928token");

        Assertions.assertEquals("REJECTED", result.getStatus());
        Assertions.assertEquals("REJECTED", result.getRiskStatus());
        verify(fundService, never()).withdrawFreeze(anyString(), any(), any(), any());
        ArgumentCaptor<WithdrawRequest> captor = ArgumentCaptor.forClass(WithdrawRequest.class);
        verify(withdrawMapper).updateById(captor.capture());
        Assertions.assertEquals("REJECTED", captor.getValue().getStatus());
    }

    @Test
    void applyRejectsInvalidClientTokenBeforeChangingFunds() {
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> service.apply(10L, 20L, new BigDecimal("100.00"), "MOCK-ACCOUNT", "bad"));

        verify(fundService, never()).withdrawFreeze(anyString(), any(), any(), any());
        verify(withdrawMapper, never()).insert(any(WithdrawRequest.class));
    }

    @Test
    void rejectByRiskUnfreezesOnceAndSkipsAlreadyRejectedRequests() {
        WithdrawRequest request = request();
        request.setRiskStatus("MANUAL_REVIEW");
        RiskCommandDTO command = RiskCommandDTO.builder()
                .commandNo("CMD1")
                .scene("WITHDRAW")
                .withdrawNo(request.getWithdrawNo())
                .command("REJECT")
                .reason("高风险提现")
                .operatorId(1L)
                .build();
        when(withdrawMapper.selectOne(any())).thenReturn(request);
        when(fundService.withdrawReject(request.getWithdrawNo(), request.getUserId(),
                request.getMerchantId(), request.getAmount()))
                .thenReturn(FundOperationResult.success("FUND_REJECT"));
        when(withdrawMapper.rejectByRisk(eq(request.getWithdrawNo()), eq("HIGH"), any(),
                eq("高风险提现"), eq(1L), any(LocalDateTime.class))).thenReturn(1);

        Assertions.assertTrue(service.rejectByRisk(command));
        request.setStatus("REJECTED");
        request.setRiskStatus("REJECTED");

        Assertions.assertFalse(service.rejectByRisk(command));

        verify(fundService, times(1)).withdrawReject(request.getWithdrawNo(), request.getUserId(),
                request.getMerchantId(), request.getAmount());
    }

    private WithdrawRequest request() {
        WithdrawRequest request = new WithdrawRequest();
        request.setWithdrawNo("W20-20260928token");
        request.setMerchantId(10L);
        request.setUserId(20L);
        request.setAmount(new BigDecimal("100.00"));
        request.setMockAccount("MOCK-ACCOUNT");
        request.setStatus("SUBMITTED");
        return request;
    }

    private Merchant merchant() {
        Merchant merchant = new Merchant();
        merchant.setId(10L);
        merchant.setUserId(20L);
        merchant.setStatus("APPROVED");
        merchant.setRiskStatus("NORMAL");
        merchant.setCreatedTime(LocalDateTime.now().minusDays(30));
        return merchant;
    }

    private RiskDecisionResult pass() {
        return RiskDecisionResult.builder()
                .action(RiskDecisionResult.ACTION_PASS)
                .riskScore(0)
                .riskLevel("LOW")
                .degraded(false)
                .message("pass")
                .hitRules(List.of())
                .build();
    }

    private RiskDecisionResult reject() {
        return RiskDecisionResult.builder()
                .action(RiskDecisionResult.ACTION_REJECT)
                .riskScore(100)
                .riskLevel("HIGH")
                .degraded(false)
                .message("高风险提现")
                .hitRules(List.of())
                .build();
    }
}
