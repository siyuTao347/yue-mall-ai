package com.example.item.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.item.dto.ReconciliationDiffDTO;
import com.example.item.entity.TradeReconciliationDiff;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
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

    @Insert("INSERT IGNORE INTO t_trade_reconciliation_diff " +
            "(diff_key, diff_type, biz_no, expected_amount, actual_amount, status, created_time) " +
            "VALUES (#{diffKey}, #{diffType}, #{bizNo}, #{expectedAmount}, #{actualAmount}, 'OPEN', #{createdTime})")
    int insertIgnore(TradeReconciliationDiff diff);
}
