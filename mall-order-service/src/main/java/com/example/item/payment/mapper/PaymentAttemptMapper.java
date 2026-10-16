package com.example.item.payment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.item.payment.domain.PaymentAttempt;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface PaymentAttemptMapper extends BaseMapper<PaymentAttempt> {

    @Select("SELECT * FROM t_payment_attempt WHERE attempt_no = #{attemptNo}")
    PaymentAttempt selectByAttemptNo(@Param("attemptNo") String attemptNo);

    @Select("SELECT * FROM t_payment_attempt WHERE attempt_no = #{attemptNo} FOR UPDATE")
    PaymentAttempt selectByAttemptNoForUpdate(@Param("attemptNo") String attemptNo);

    @Select("SELECT * FROM t_payment_attempt WHERE payment_no = #{paymentNo} ORDER BY attempt_seq DESC")
    List<PaymentAttempt> listByPaymentNo(@Param("paymentNo") String paymentNo);

    @Select("SELECT * FROM t_payment_attempt WHERE status IN ('PENDING', 'UNKNOWN', 'SUBMITTING') " +
            "AND next_query_at <= #{now} LIMIT #{limit}")
    List<PaymentAttempt> selectPendingQueries(@Param("now") LocalDateTime now, @Param("limit") int limit);

    @Update("UPDATE t_payment_attempt SET status = #{status}, failure_code = #{failureCode}, " +
            "updated_time = #{now}, version = version + 1 WHERE attempt_no = #{attemptNo}")
    int updateStatus(@Param("attemptNo") String attemptNo, @Param("status") String status,
                     @Param("failureCode") String failureCode, @Param("now") LocalDateTime now);

    @Select("SELECT COALESCE(MAX(attempt_seq), 0) FROM t_payment_attempt WHERE payment_no = #{paymentNo}")
    int getMaxAttemptSeq(@Param("paymentNo") String paymentNo);
}
