package com.example.user.service;

import com.example.user.dto.MissingFundFlowDTO;
import com.example.user.dto.PlatformAccountBalanceDTO;
import com.example.user.dto.UserAccountBalanceDTO;
import com.example.user.dto.WithdrawFundBalanceDTO;
import com.example.user.entity.FundReconciliationDiff;
import com.example.user.mapper.FundReconciliationDiffMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Service
@Slf4j
public class FundReconciliationService {
    private final FundReconciliationDiffMapper diffMapper;

    public FundReconciliationService(FundReconciliationDiffMapper diffMapper) {
        this.diffMapper = diffMapper;
    }

    public int reconcile() {
        int diffs = reconcileUserBalances();
        diffs += reconcilePlatformBalance();
        diffs += reconcileWithdrawRequests();
        diffs += reconcileMissingFundFlows();
        if (diffs > 0) {
            log.warn("资金对账发现 {} 条差异，已记录待人工处理", diffs);
        }
        return diffs;
    }

    private int reconcileUserBalances() {
        int diffs = 0;
        for (UserAccountBalanceDTO balance : diffMapper.selectUserBalances()) {
            diffs += saveDiff("USER_AVAILABLE", String.valueOf(balance.getUserId()),
                    balance.getAvailableAmount(), balance.getFlowAvailableAmount());
            diffs += saveDiff("USER_PENDING_SETTLE", String.valueOf(balance.getUserId()),
                    balance.getPendingSettleAmount(), balance.getFlowPendingSettleAmount());
            diffs += saveDiff("USER_FROZEN", String.valueOf(balance.getUserId()),
                    balance.getFrozenAmount(), balance.getFlowFrozenAmount());
        }
        return diffs;
    }

    private int reconcilePlatformBalance() {
        PlatformAccountBalanceDTO balance = diffMapper.selectPlatformBalance();
        if (balance == null) {
            return saveDiff("PLATFORM_ACCOUNT", "PLATFORM_MAIN", BigDecimal.ONE, BigDecimal.ZERO);
        }
        return saveDiff("PLATFORM_ESCROW", "PLATFORM_MAIN", balance.getEscrowAmount(),
                        balance.getFlowEscrowAmount())
                + saveDiff("PLATFORM_REVENUE", "PLATFORM_MAIN", balance.getRevenueAmount(),
                        balance.getFlowRevenueAmount())
                + saveDiff("PLATFORM_WITHDRAW_PENDING", "PLATFORM_MAIN", balance.getWithdrawPendingAmount(),
                        balance.getFlowWithdrawPendingAmount())
                + saveDiff("PLATFORM_DEPOSIT", "PLATFORM_MAIN", balance.getDepositAmount(),
                        balance.getFlowDepositAmount());
    }

    private int reconcileWithdrawRequests() {
        int diffs = 0;
        for (WithdrawFundBalanceDTO withdraw : diffMapper.selectWithdrawFundBalances()) {
            diffs += saveDiff("WITHDRAW_FREEZE", withdraw.getWithdrawNo(),
                    withdraw.getAmount(), withdraw.getFreezeAmount());
            if (!hasValidWithdrawFundStatus(withdraw)) {
                BigDecimal actual = withdraw.getPayoutAmount().max(withdraw.getRejectAmount());
                diffs += saveDiff("WITHDRAW_STATUS", withdraw.getWithdrawNo(),
                        withdraw.getAmount(), actual);
            }
        }
        return diffs;
    }

    private boolean hasValidWithdrawFundStatus(WithdrawFundBalanceDTO withdraw) {
        BigDecimal amount = withdraw.getAmount();
        boolean payoutMatched = amount.compareTo(withdraw.getPayoutAmount()) == 0;
        boolean rejectMatched = amount.compareTo(withdraw.getRejectAmount()) == 0;
        boolean noPayout = BigDecimal.ZERO.compareTo(withdraw.getPayoutAmount()) == 0;
        boolean noReject = BigDecimal.ZERO.compareTo(withdraw.getRejectAmount()) == 0;
        return switch (withdraw.getStatus()) {
            case "SUBMITTED" -> noPayout && noReject;
            case "PAYOUT_SUCCESS" -> payoutMatched && noReject;
            case "REJECTED" -> rejectMatched && noPayout;
            default -> false;
        };
    }

    private int reconcileMissingFundFlows() {
        int diffs = 0;
        for (MissingFundFlowDTO missing : diffMapper.selectMissingFundFlows()) {
            diffs += saveDiff("FUND_FLOW_MISSING", missing.getTransactionNo(),
                    missing.getAmount(), BigDecimal.ZERO);
        }
        return diffs;
    }

    private int saveDiff(String diffType, String bizNo, BigDecimal expected, BigDecimal actual) {
        if (expected != null && actual != null && expected.compareTo(actual) == 0) {
            return 0;
        }
        FundReconciliationDiff diff = new FundReconciliationDiff();
        diff.setDiffKey(diffType + ":" + bizNo);
        diff.setDiffType(diffType);
        diff.setBizNo(bizNo);
        diff.setExpectedAmount(zeroIfNull(expected));
        diff.setActualAmount(zeroIfNull(actual));
        diff.setStatus("OPEN");
        diff.setCreatedTime(LocalDateTime.now());
        diffMapper.insertIgnore(diff);
        return 1;
    }

    private BigDecimal zeroIfNull(BigDecimal amount) {
        return amount == null ? BigDecimal.ZERO : amount;
    }
}
