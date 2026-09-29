package com.example.item.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.item.entity.TradeOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface TradeOrderMapper extends BaseMapper<TradeOrder> {
    @Select("SELECT * FROM t_trade_order WHERE order_no = #{orderNo} FOR UPDATE")
    TradeOrder selectByOrderNoForUpdate(@Param("orderNo") String orderNo);

    @Select("SELECT * FROM t_trade_order WHERE order_status = 'WAIT_PAY' " +
            "AND pay_deadline < #{now} ORDER BY id LIMIT #{limit}")
    List<TradeOrder> selectExpiredPayOrders(@Param("now") LocalDateTime now, @Param("limit") int limit);

    @Select("SELECT * FROM t_trade_order WHERE order_status = 'DELIVERED' " +
            "AND dispute_status = 'NONE' AND auto_confirm_time <= #{now} ORDER BY id LIMIT #{limit}")
    List<TradeOrder> selectAutoConfirmOrders(@Param("now") LocalDateTime now, @Param("limit") int limit);

    @Select("SELECT * FROM t_trade_order WHERE order_status IN ('CONFIRMED', 'SETTLING') " +
            "AND escrow_status = 'SETTLE_PENDING' AND settle_available_time <= #{now} " +
            "ORDER BY id LIMIT #{limit}")
    List<TradeOrder> selectSettleableOrders(@Param("now") LocalDateTime now, @Param("limit") int limit);

    @Select("SELECT * FROM t_trade_order WHERE order_status = 'PAID' " +
            "AND escrow_status = 'FROZEN' AND dispute_status = 'NONE' AND delivery_deadline < #{now} " +
            "ORDER BY id LIMIT #{limit}")
    List<TradeOrder> selectDeliveryTimeoutOrders(@Param("now") LocalDateTime now, @Param("limit") int limit);

    @Update("UPDATE t_trade_order SET order_status = 'PAID', pay_status = 'SUCCESS', " +
            "escrow_status = 'FREEZE_PENDING', delivery_deadline = #{now} + INTERVAL " +
            "#{deliveryTimeoutMinutes} MINUTE, version = version + 1, updated_time = #{now} " +
            "WHERE order_no = #{orderNo} AND order_status = 'WAIT_PAY' AND pay_status = 'INIT' " +
            "AND pay_deadline >= #{now}")
    int markPaid(@Param("orderNo") String orderNo, @Param("now") LocalDateTime now,
                 @Param("deliveryTimeoutMinutes") int deliveryTimeoutMinutes);

    @Update("UPDATE t_trade_order SET escrow_status = 'FROZEN', version = version + 1, " +
            "updated_time = #{now} WHERE order_no = #{orderNo} AND order_status = 'PAID' " +
            "AND escrow_status = 'FREEZE_PENDING'")
    int markEscrowFrozen(@Param("orderNo") String orderNo, @Param("now") LocalDateTime now);

    @Update("UPDATE t_trade_order SET order_status = 'CANCELLING', pay_status = 'TIMEOUT', " +
            "version = version + 1, updated_time = #{now} WHERE order_no = #{orderNo} " +
            "AND order_status = 'WAIT_PAY' AND pay_deadline < #{now}")
    int markCancellingFromWaitPay(@Param("orderNo") String orderNo, @Param("now") LocalDateTime now);

    @Update("UPDATE t_trade_order SET order_status = 'CANCELLING', pay_status = 'CANCELLED', " +
            "version = version + 1, updated_time = #{now} WHERE order_no = #{orderNo} " +
            "AND order_status = 'WAIT_PAY' AND pay_status = 'INIT'")
    int markCancellingByBuyer(@Param("orderNo") String orderNo, @Param("now") LocalDateTime now);

    @Update("UPDATE t_trade_order SET order_status = 'CANCELLED', version = version + 1, " +
            "updated_time = #{now} WHERE order_no = #{orderNo} AND order_status = 'CANCELLING'")
    int markCancelled(@Param("orderNo") String orderNo, @Param("now") LocalDateTime now);

    @Update("UPDATE t_trade_order SET order_status = 'DELIVERED', delivery_status = 'DELIVERED', " +
            "delivered_time = #{now}, auto_confirm_time = #{now} + INTERVAL #{autoConfirmHours} HOUR, " +
            "version = version + 1, updated_time = #{now} " +
            "WHERE order_no = #{orderNo} AND order_status = 'PAID' AND delivery_status = 'WAIT_DELIVERY' " +
            "AND dispute_status = 'NONE' AND delivery_deadline >= #{now}")
    int markDelivered(@Param("orderNo") String orderNo, @Param("now") LocalDateTime now,
                      @Param("autoConfirmHours") int autoConfirmHours);

    @Update("UPDATE t_trade_order SET order_status = 'SETTLING', escrow_status = 'SETTLE_PENDING', " +
            "version = version + 1, updated_time = #{now} WHERE order_no = #{orderNo} " +
            "AND order_status = 'CONFIRMED' AND escrow_status = 'SETTLE_PENDING' " +
            "AND settle_available_time <= #{now}")
    int markSettling(@Param("orderNo") String orderNo, @Param("now") LocalDateTime now);

    @Update("UPDATE t_trade_order SET order_status = 'SETTLING', delivery_status = 'CONFIRMED', " +
            "confirmed_time = #{now}, escrow_status = 'SETTLE_PENDING', settle_available_time = #{now}, " +
            "version = version + 1, updated_time = #{now} WHERE order_no = #{orderNo} " +
            "AND order_status = 'DELIVERED' AND escrow_status = 'FROZEN' " +
            "AND dispute_status = 'ARBITRATING'")
    int markSettlingByArbitration(@Param("orderNo") String orderNo, @Param("now") LocalDateTime now);

    @Update("UPDATE t_trade_order SET order_status = 'SETTLED', escrow_status = 'SETTLED', " +
            "settled_time = #{now}, version = version + 1, updated_time = #{now} " +
            "WHERE order_no = #{orderNo} AND order_status = 'SETTLING' AND escrow_status = 'SETTLE_PENDING'")
    int markSettled(@Param("orderNo") String orderNo, @Param("now") LocalDateTime now);

    @Update("UPDATE t_trade_order SET delivery_status = 'VIEWED', version = version + 1, " +
            "updated_time = #{now} WHERE order_no = #{orderNo} AND delivery_status = 'DELIVERED' " +
            "AND order_status = 'DELIVERED'")
    int markDeliveryViewed(@Param("orderNo") String orderNo, @Param("now") LocalDateTime now);

    @Update("UPDATE t_trade_order SET order_status = 'CONFIRMED', delivery_status = 'CONFIRMED', " +
            "confirmed_time = #{now}, escrow_status = 'SETTLE_PENDING', " +
            "settle_available_time = #{now} + INTERVAL #{settleCooldownHours} HOUR, version = version + 1, " +
            "updated_time = #{now} WHERE order_no = #{orderNo} AND order_status = 'DELIVERED' " +
            "AND delivery_status IN ('DELIVERED', 'VIEWED') AND dispute_status = 'NONE' " +
            "AND auto_confirm_time <= #{now}")
    int markConfirmedForAutoConfirm(@Param("orderNo") String orderNo, @Param("now") LocalDateTime now,
                                    @Param("settleCooldownHours") int settleCooldownHours);

    @Update("UPDATE t_trade_order SET order_status = 'CONFIRMED', delivery_status = 'CONFIRMED', " +
            "confirmed_time = #{now}, escrow_status = 'SETTLE_PENDING', " +
            "settle_available_time = #{now} + INTERVAL #{settleCooldownHours} HOUR, version = version + 1, " +
            "updated_time = #{now} WHERE order_no = #{orderNo} AND order_status = 'DELIVERED' " +
            "AND delivery_status IN ('DELIVERED', 'VIEWED') AND dispute_status = 'NONE'")
    int markConfirmedByBuyer(@Param("orderNo") String orderNo, @Param("now") LocalDateTime now,
                             @Param("settleCooldownHours") int settleCooldownHours);

    @Update("UPDATE t_trade_order SET order_status = 'REFUNDED', escrow_status = 'REFUNDED', " +
            "version = version + 1, updated_time = #{now} WHERE order_no = #{orderNo} " +
            "AND order_status IN ('PAID', 'DELIVERED') AND escrow_status = 'FROZEN' " +
            "AND dispute_status IN ('NONE', 'RESOLVED', 'ARBITRATING')")
    int markRefunded(@Param("orderNo") String orderNo, @Param("now") LocalDateTime now);

    @Update("UPDATE t_trade_order SET dispute_status = #{status}, version = version + 1, " +
            "updated_time = #{now} WHERE order_no = #{orderNo}")
    int markDispute(@Param("orderNo") String orderNo, @Param("status") String status,
                    @Param("now") LocalDateTime now);

    @Update("UPDATE t_trade_order SET fee_amount = #{feeAmount}, seller_income = #{sellerIncome} " +
            "WHERE id = #{id}")
    int updateFeeAndIncome(@Param("id") Long id, @Param("feeAmount") BigDecimal feeAmount,
                           @Param("sellerIncome") BigDecimal sellerIncome);

    @Update("UPDATE t_trade_order SET risk_status = #{riskStatus}, risk_level = #{riskLevel}, " +
            "risk_decision_no = #{decisionNo}, risk_reason = #{reason}, version = version + 1, " +
            "updated_time = #{now} WHERE order_no = #{orderNo} AND risk_status <> #{riskStatus}")
    int updateRiskStatus(@Param("orderNo") String orderNo, @Param("riskStatus") String riskStatus,
                         @Param("riskLevel") String riskLevel, @Param("decisionNo") String decisionNo,
                         @Param("reason") String reason, @Param("now") LocalDateTime now);

    @Update("UPDATE t_trade_order SET risk_status = #{riskStatus}, risk_level = #{riskLevel}, " +
            "risk_decision_no = #{decisionNo}, risk_reason = #{reason}, version = version + 1, " +
            "updated_time = #{now} WHERE order_no = #{orderNo} " +
            "AND order_status IN ('WAIT_PAY', 'PAID', 'DELIVERED', 'CONFIRMED', 'SETTLING') " +
            "AND risk_status <> #{riskStatus}")
    int updateRiskStatusIfAllowed(@Param("orderNo") String orderNo, @Param("riskStatus") String riskStatus,
                                  @Param("riskLevel") String riskLevel, @Param("decisionNo") String decisionNo,
                                  @Param("reason") String reason, @Param("now") LocalDateTime now);

    @Update("UPDATE t_trade_order SET settle_available_time = GREATEST(settle_available_time, #{until}), " +
            "version = version + 1, updated_time = #{now} WHERE order_no = #{orderNo} " +
            "AND order_status IN ('CONFIRMED', 'SETTLING') AND escrow_status = 'SETTLE_PENDING' " +
            "AND settle_available_time < #{until}")
    int delaySettlement(@Param("orderNo") String orderNo, @Param("until") LocalDateTime until,
                        @Param("now") LocalDateTime now);
}
