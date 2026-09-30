package com.example.item.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.item.dto.ReconciliationDiffDTO;
import com.example.item.entity.TradeReconciliationDiff;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface TradeReconciliationDiffMapper extends BaseMapper<TradeReconciliationDiff> {
    @Select("SELECT order_no AS bizNo, order_amount AS expectedAmount, " +
            "fee_amount + seller_income AS actualAmount " +
            "FROM t_trade_order WHERE order_status IN ('SETTLING', 'SETTLED') " +
            "AND order_amount <> fee_amount + seller_income")
    List<ReconciliationDiffDTO> selectOrderAmountDiffs();

    @Select("SELECT o.order_no AS bizNo, o.order_amount AS expectedAmount, " +
            "COALESCE(s.order_amount, 0) AS actualAmount " +
            "FROM t_trade_order o LEFT JOIN t_order_settlement s ON s.order_no = o.order_no " +
            "WHERE o.order_status IN ('SETTLING', 'SETTLED') " +
            "AND (s.id IS NULL OR s.order_amount <> o.order_amount " +
            "OR s.fee_amount + s.seller_income <> s.order_amount " +
            "OR s.status <> 'SUCCESS' OR s.fund_transaction_no IS NULL)")
    List<ReconciliationDiffDTO> selectSettlementDiffs();

    @Select("SELECT COALESCE(SUM(order_amount), 0) FROM t_trade_order " +
            "WHERE pay_status = 'SUCCESS' AND escrow_status IN ('FROZEN', 'SETTLE_PENDING')")
    BigDecimal sumActiveEscrowAmount();

    @Select("""
            SELECT p.order_no AS bizNo, 1 AS expectedAmount, 0 AS actualAmount
            FROM t_payment_callback c
            JOIN t_payment_order p ON p.payment_no = c.payment_no
            JOIN t_trade_order o ON o.order_no = p.order_no
            LEFT JOIN t_trade_orchestration_task t
              ON t.idempotency_key = CONCAT('PAY_CONFIRM:', p.order_no)
            WHERE c.verify_status = 'VERIFY_PASSED'
              AND c.result = 'SUCCESS'
              AND (o.order_status = 'PAY_CONFIRMING' OR p.status = 'SUCCESS_PENDING')
              AND t.id IS NULL
            """)
    List<ReconciliationDiffDTO> selectCallbackWithoutTask();

    @Select("""
            SELECT task_no AS bizNo, 1 AS expectedAmount, 0 AS actualAmount
            FROM t_trade_orchestration_task
            WHERE status IN ('RUNNING', 'FAILED')
              AND updated_time <= #{interruptedBefore}
            """)
    List<ReconciliationDiffDTO> selectInterruptedTasks(@Param("interruptedBefore") LocalDateTime interruptedBefore);

    @Select("""
            SELECT o.order_no AS bizNo, 1 AS expectedAmount, 0 AS actualAmount
            FROM t_trade_order o
            LEFT JOIN db_item.t_asset_reservation ar ON ar.order_no = o.order_no
            WHERE (
                (o.order_status = 'CREATE_PENDING'
                    AND COALESCE(ar.status, 'MISSING') NOT IN ('MISSING', 'RESERVED'))
                OR (o.order_status = 'WAIT_PAY'
                    AND COALESCE(ar.status, 'MISSING') <> 'RESERVED')
                OR (o.order_status = 'CANCELLING'
                    AND COALESCE(ar.status, 'MISSING') NOT IN ('RESERVED', 'RELEASED'))
                OR (o.order_status IN ('CANCELLED', 'CLOSED', 'REJECTED')
                    AND COALESCE(ar.status, 'MISSING') NOT IN ('MISSING', 'RELEASED'))
                OR (o.order_status = 'PAY_CONFIRMING'
                    AND COALESCE(ar.status, 'MISSING') <> 'RESERVED')
                OR (o.order_status IN ('PAID', 'DELIVERED', 'CONFIRMED', 'SETTLING', 'SETTLED')
                    AND COALESCE(ar.status, 'MISSING') <> 'CONFIRMED')
                OR (o.order_status = 'REFUNDING'
                    AND COALESCE(ar.status, 'MISSING') NOT IN ('CONFIRMED', 'REFUNDED'))
                OR (o.order_status = 'REFUNDED'
                    AND COALESCE(ar.status, 'MISSING') <> 'REFUNDED')
            )
            """)
    List<ReconciliationDiffDTO> selectAssetOrderMismatches();

    @Select("""
            SELECT o.order_no AS bizNo, o.order_amount AS expectedAmount, 0 AS actualAmount
            FROM t_trade_order o
            LEFT JOIN db_user.t_fund_transaction f
              ON f.order_no = o.order_no
             AND f.business_type = 'PAY_FREEZE'
             AND f.status = 'SUCCESS'
            WHERE f.id IS NULL AND (
                (o.pay_status = 'SUCCESS'
                    AND o.escrow_status IN ('FROZEN', 'SETTLE_PENDING', 'SETTLED', 'REFUNDED'))
                OR EXISTS (
                    SELECT 1 FROM t_trade_orchestration_step s
                    WHERE s.task_no = CONCAT('PAY-', o.order_no)
                      AND s.step_name = 'FUND_FREEZE'
                      AND s.status = 'SUCCESS'
                )
            )
            """)
    List<ReconciliationDiffDTO> selectFundTransactionMissing();

    @Insert("INSERT IGNORE INTO t_trade_reconciliation_diff " +
            "(diff_key, diff_type, biz_no, expected_amount, actual_amount, status, created_time) " +
            "VALUES (#{diffKey}, #{diffType}, #{bizNo}, #{expectedAmount}, #{actualAmount}, 'OPEN', #{createdTime})")
    int insertIgnore(TradeReconciliationDiff diff);
}
