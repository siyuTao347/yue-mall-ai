package com.example.item.payment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.item.payment.domain.PaymentCommand;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface PaymentCommandMapper extends BaseMapper<PaymentCommand> {

    @Select("SELECT * FROM t_payment_command WHERE status = 'PENDING' " +
            "AND (next_execute_at IS NULL OR next_execute_at <= #{now}) " +
            "AND (locked_until IS NULL OR locked_until <= #{now}) " +
            "ORDER BY id ASC LIMIT #{limit}")
    List<PaymentCommand> selectExecutable(@Param("now") LocalDateTime now, @Param("limit") int limit);

    @Update("UPDATE t_payment_command SET status = 'EXECUTING', lease_token = #{leaseToken}, " +
            "locked_until = #{lockedUntil}, updated_time = #{now} " +
            "WHERE id = #{id} AND (locked_until IS NULL OR locked_until <= #{now}) AND status = 'PENDING'")
    int tryClaim(@Param("id") Long id, @Param("leaseToken") String leaseToken,
                 @Param("lockedUntil") LocalDateTime lockedUntil, @Param("now") LocalDateTime now);
}
