package com.example.item.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.item.entity.PaymentOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface PaymentOrderMapper extends BaseMapper<PaymentOrder> {
    @Update("UPDATE t_payment_order SET status = 'SUCCESS_PENDING', updated_time = #{now} " +
            "WHERE payment_no = #{paymentNo} AND status IN ('INIT', 'PAYING')")
    int markSuccessPending(@Param("paymentNo") String paymentNo, @Param("now") LocalDateTime now);

    @Update("UPDATE t_payment_order SET status = 'SUCCESS', updated_time = #{now} " +
            "WHERE payment_no = #{paymentNo} AND status IN ('INIT', 'PAYING', 'SUCCESS_PENDING')")
    int markSuccess(@Param("paymentNo") String paymentNo, @Param("now") LocalDateTime now);

    @Update("UPDATE t_payment_order SET status = 'TIMEOUT', updated_time = #{now} " +
            "WHERE order_no = #{orderNo} AND status IN ('INIT', 'PAYING')")
    int markTimeout(@Param("orderNo") String orderNo, @Param("now") LocalDateTime now);

    @Update("UPDATE t_payment_order SET status = 'CANCELLED', updated_time = #{now} " +
            "WHERE order_no = #{orderNo} AND status IN ('INIT', 'PAYING')")
    int closeByOrder(@Param("orderNo") String orderNo, @Param("now") LocalDateTime now);

    @Update("UPDATE t_payment_order SET status = 'LATE_SUCCESS', updated_time = #{now} " +
            "WHERE payment_no = #{paymentNo} AND status IN ('INIT', 'PAYING', 'TIMEOUT', 'CANCELLED')")
    int markLateSuccess(@Param("paymentNo") String paymentNo, @Param("now") LocalDateTime now);

    @Update("UPDATE t_payment_order SET status = 'FAIL', updated_time = #{now} " +
            "WHERE payment_no = #{paymentNo} AND status IN ('INIT', 'PAYING')")
    int markFailed(@Param("paymentNo") String paymentNo, @Param("now") LocalDateTime now);

    @Update("UPDATE t_payment_order SET status = 'PAYING', updated_time = #{now} " +
            "WHERE order_no = #{orderNo} AND status = 'INIT' AND expire_time > #{now}")
    int startPaying(@Param("orderNo") String orderNo, @Param("now") LocalDateTime now);
}
