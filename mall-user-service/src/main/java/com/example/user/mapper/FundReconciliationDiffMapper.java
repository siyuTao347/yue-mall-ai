package com.example.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.user.dto.MissingFundFlowDTO;
import com.example.user.dto.PlatformAccountBalanceDTO;
import com.example.user.dto.UserAccountBalanceDTO;
import com.example.user.dto.WithdrawFundBalanceDTO;
import com.example.user.entity.FundReconciliationDiff;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface FundReconciliationDiffMapper extends BaseMapper<FundReconciliationDiff> {
    @Select("SELECT ua.user_id AS userId, ua.available_amount AS availableAmount, " +
            "ua.pending_settle_amount AS pendingSettleAmount, ua.frozen_amount AS frozenAmount, " +
            "COALESCE(SUM(CASE WHEN ff.account_type = 'AVAILABLE' " +
            "THEN IF(ff.direction = 'CREDIT', ff.amount, -ff.amount) ELSE 0 END), 0) AS flowAvailableAmount, " +
            "COALESCE(SUM(CASE WHEN ff.account_type = 'PENDING_SETTLE' " +
            "THEN IF(ff.direction = 'CREDIT', ff.amount, -ff.amount) ELSE 0 END), 0) AS flowPendingSettleAmount, " +
            "COALESCE(SUM(CASE WHEN ff.account_type = 'FROZEN' " +
            "THEN IF(ff.direction = 'CREDIT', ff.amount, -ff.amount) ELSE 0 END), 0) AS flowFrozenAmount " +
            "FROM t_user_account ua " +
            "LEFT JOIN t_fund_flow ff ON ff.owner_type = 'USER' AND ff.owner_id = ua.user_id " +
            "GROUP BY ua.user_id")
    List<UserAccountBalanceDTO> selectUserBalances();

    @Select("SELECT pa.escrow_amount AS escrowAmount, pa.revenue_amount AS revenueAmount, " +
            "pa.withdraw_pending_amount AS withdrawPendingAmount, pa.deposit_amount AS depositAmount, " +
            "COALESCE(SUM(CASE WHEN ff.account_type = 'ESCROW' " +
            "THEN IF(ff.direction = 'CREDIT', ff.amount, -ff.amount) ELSE 0 END), 0) AS flowEscrowAmount, " +
            "COALESCE(SUM(CASE WHEN ff.account_type = 'REVENUE' " +
            "THEN IF(ff.direction = 'CREDIT', ff.amount, -ff.amount) ELSE 0 END), 0) AS flowRevenueAmount, " +
            "COALESCE(SUM(CASE WHEN ff.account_type = 'WITHDRAW_PENDING' " +
            "THEN IF(ff.direction = 'CREDIT', ff.amount, -ff.amount) ELSE 0 END), 0) AS flowWithdrawPendingAmount, " +
            "COALESCE(SUM(CASE WHEN ff.account_type = 'DEPOSIT' " +
            "THEN IF(ff.direction = 'CREDIT', ff.amount, -ff.amount) ELSE 0 END), 0) AS flowDepositAmount " +
            "FROM t_platform_account pa " +
            "LEFT JOIN t_fund_flow ff ON ff.owner_type = 'PLATFORM' " +
            "WHERE pa.account_code = 'PLATFORM_MAIN' GROUP BY pa.id")
    PlatformAccountBalanceDTO selectPlatformBalance();

    @Select("SELECT wr.withdraw_no AS withdrawNo, wr.status, wr.amount, " +
            "COALESCE(SUM(CASE WHEN ft.business_type = 'WITHDRAW_FREEZE' THEN ft.amount ELSE 0 END), 0) AS freezeAmount, " +
            "COALESCE(SUM(CASE WHEN ft.business_type = 'WITHDRAW_PAYOUT' THEN ft.amount ELSE 0 END), 0) AS payoutAmount, " +
            "COALESCE(SUM(CASE WHEN ft.business_type = 'WITHDRAW_REJECT' THEN ft.amount ELSE 0 END), 0) AS rejectAmount " +
            "FROM t_withdraw_request wr " +
            "LEFT JOIN t_fund_transaction ft ON ft.withdraw_no = wr.withdraw_no AND ft.status = 'SUCCESS' " +
            "GROUP BY wr.id, wr.withdraw_no, wr.status, wr.amount")
    List<WithdrawFundBalanceDTO> selectWithdrawFundBalances();

    @Select("SELECT ft.transaction_no AS transactionNo, ft.amount " +
            "FROM t_fund_transaction ft LEFT JOIN t_fund_flow ff ON ff.transaction_no = ft.transaction_no " +
            "WHERE ft.status = 'SUCCESS' AND ff.id IS NULL ORDER BY ft.id LIMIT 1000")
    List<MissingFundFlowDTO> selectMissingFundFlows();

    @Insert("INSERT IGNORE INTO t_fund_reconciliation_diff " +
            "(diff_key, diff_type, biz_no, expected_amount, actual_amount, status, created_time) " +
            "VALUES (#{diffKey}, #{diffType}, #{bizNo}, #{expectedAmount}, #{actualAmount}, 'OPEN', #{createdTime})")
    int insertIgnore(FundReconciliationDiff diff);
}
