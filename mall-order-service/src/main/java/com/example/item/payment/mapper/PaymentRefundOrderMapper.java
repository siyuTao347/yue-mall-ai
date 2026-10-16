package com.example.item.payment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.item.payment.domain.PaymentRefundOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface PaymentRefundOrderMapper extends BaseMapper<PaymentRefundOrder> {

    @Select("SELECT * FROM t_payment_refund_order WHERE refund_no = #{refundNo}")
    PaymentRefundOrder selectByRefundNo(@Param("refundNo") String refundNo);

    @Select("SELECT * FROM t_payment_refund_order WHERE refund_no = #{refundNo} FOR UPDATE")
    PaymentRefundOrder selectByRefundNoForUpdate(@Param("refundNo") String refundNo);

    @Select("SELECT COALESCE(SUM(amount), 0) FROM t_payment_refund_order " +
            "WHERE original_attempt_no = #{attemptNo} AND status IN ('REQUESTED', 'SUBMITTING', 'ACCEPTED', 'PROCESSING', 'UNKNOWN')")
    BigDecimal sumReservedRefundAmount(@Param("attemptNo") String attemptNo);

    @Select("SELECT COALESCE(SUM(amount), 0) FROM t_payment_refund_order " +
            "WHERE original_attempt_no = #{attemptNo} AND status = 'SUCCEEDED'")
    BigDecimal sumSucceededRefundAmount(@Param("attemptNo") String attemptNo);

    @Select("SELECT * FROM t_payment_refund_order WHERE status IN ('PROCESSING', 'UNKNOWN') " +
            "AND next_query_at <= #{now} LIMIT #{limit}")
    List<PaymentRefundOrder> selectPendingQueries(@Param("now") LocalDateTime now, @Param("limit") int limit);
}
