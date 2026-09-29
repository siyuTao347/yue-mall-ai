package com.example.user.service;

import com.example.user.dto.MissingFundFlowDTO;
import com.example.user.dto.PlatformAccountBalanceDTO;
import com.example.user.dto.UserAccountBalanceDTO;
import com.example.user.dto.WithdrawFundBalanceDTO;
import com.example.user.entity.FundReconciliationDiff;
import com.example.user.mapper.FundReconciliationDiffMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FundReconciliationServiceTest {
    private FundReconciliationDiffMapper diffMapper;
    private FundReconciliationService service;

    @BeforeEach
    void setUp() {
        diffMapper = mock(FundReconciliationDiffMapper.class);
        service = new FundReconciliationService(diffMapper);
    }

    @Test
    void reconcileRecordsUserBalanceDiffWithoutAutoRepair() {
        UserAccountBalanceDTO balance = new UserAccountBalanceDTO();
        balance.setUserId(20L);
        balance.setAvailableAmount(new BigDecimal("90.00"));
        balance.setFlowAvailableAmount(new BigDecimal("100.00"));
        balance.setPendingSettleAmount(BigDecimal.ZERO);
        balance.setFlowPendingSettleAmount(BigDecimal.ZERO);
        balance.setFrozenAmount(BigDecimal.ZERO);
        balance.setFlowFrozenAmount(BigDecimal.ZERO);
        when(diffMapper.selectUserBalances()).thenReturn(List.of(balance));
        when(diffMapper.selectPlatformBalance()).thenReturn(matchedPlatform());
        when(diffMapper.selectWithdrawFundBalances()).thenReturn(List.of());
        when(diffMapper.selectMissingFundFlows()).thenReturn(List.of());

        int diffs = service.reconcile();

        Assertions.assertEquals(1, diffs);
        ArgumentCaptor<FundReconciliationDiff> captor = ArgumentCaptor.forClass(FundReconciliationDiff.class);
        verify(diffMapper).insertIgnore(captor.capture());
        Assertions.assertEquals("USER_AVAILABLE:20", captor.getValue().getDiffKey());
        Assertions.assertEquals("OPEN", captor.getValue().getStatus());
    }

    @Test
    void reconcileRecordsInvalidWithdrawFundStatus() {
        UserAccountBalanceDTO balance = matchedUser();
        WithdrawFundBalanceDTO withdraw = new WithdrawFundBalanceDTO();
        withdraw.setWithdrawNo("W1");
        withdraw.setStatus("PAYOUT_SUCCESS");
        withdraw.setAmount(new BigDecimal("100.00"));
        withdraw.setFreezeAmount(new BigDecimal("100.00"));
        withdraw.setPayoutAmount(BigDecimal.ZERO);
        withdraw.setRejectAmount(BigDecimal.ZERO);
        when(diffMapper.selectUserBalances()).thenReturn(List.of(balance));
        when(diffMapper.selectPlatformBalance()).thenReturn(matchedPlatform());
        when(diffMapper.selectWithdrawFundBalances()).thenReturn(List.of(withdraw));
        when(diffMapper.selectMissingFundFlows()).thenReturn(List.of());

        int diffs = service.reconcile();

        Assertions.assertEquals(1, diffs);
        verify(diffMapper).insertIgnore(any(FundReconciliationDiff.class));
    }

    @Test
    void reconcileDoesNotWriteDiffWhenAllBalancesMatch() {
        WithdrawFundBalanceDTO withdraw = new WithdrawFundBalanceDTO();
        withdraw.setWithdrawNo("W1");
        withdraw.setStatus("SUBMITTED");
        withdraw.setAmount(new BigDecimal("100.00"));
        withdraw.setFreezeAmount(new BigDecimal("100.00"));
        withdraw.setPayoutAmount(BigDecimal.ZERO);
        withdraw.setRejectAmount(BigDecimal.ZERO);
        when(diffMapper.selectUserBalances()).thenReturn(List.of(matchedUser()));
        when(diffMapper.selectPlatformBalance()).thenReturn(matchedPlatform());
        when(diffMapper.selectWithdrawFundBalances()).thenReturn(List.of(withdraw));
        when(diffMapper.selectMissingFundFlows()).thenReturn(List.of());

        int diffs = service.reconcile();

        Assertions.assertEquals(0, diffs);
        verify(diffMapper, never()).insertIgnore(any(FundReconciliationDiff.class));
    }

    private UserAccountBalanceDTO matchedUser() {
        UserAccountBalanceDTO balance = new UserAccountBalanceDTO();
        balance.setUserId(20L);
        balance.setAvailableAmount(BigDecimal.ZERO);
        balance.setFlowAvailableAmount(BigDecimal.ZERO);
        balance.setPendingSettleAmount(BigDecimal.ZERO);
        balance.setFlowPendingSettleAmount(BigDecimal.ZERO);
        balance.setFrozenAmount(BigDecimal.ZERO);
        balance.setFlowFrozenAmount(BigDecimal.ZERO);
        return balance;
    }

    private PlatformAccountBalanceDTO matchedPlatform() {
        PlatformAccountBalanceDTO balance = new PlatformAccountBalanceDTO();
        balance.setEscrowAmount(BigDecimal.ZERO);
        balance.setFlowEscrowAmount(BigDecimal.ZERO);
        balance.setRevenueAmount(BigDecimal.ZERO);
        balance.setFlowRevenueAmount(BigDecimal.ZERO);
        balance.setWithdrawPendingAmount(BigDecimal.ZERO);
        balance.setFlowWithdrawPendingAmount(BigDecimal.ZERO);
        balance.setDepositAmount(BigDecimal.ZERO);
        balance.setFlowDepositAmount(BigDecimal.ZERO);
        return balance;
    }
}
