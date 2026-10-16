package com.example.item.payment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.item.payment.domain.PaymentEventInbox;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface PaymentEventInboxMapper extends BaseMapper<PaymentEventInbox> {

    @Select("SELECT * FROM t_payment_event_inbox WHERE event_id = #{eventId}")
    PaymentEventInbox selectByEventId(@Param("eventId") String eventId);

    @Select("SELECT * FROM t_payment_event_inbox WHERE processing_status IN ('RECEIVED', 'FAILED') " +
            "AND (next_execute_at IS NULL OR next_execute_at <= #{now}) " +
            "ORDER BY id ASC LIMIT #{limit}")
    List<PaymentEventInbox> selectProcessable(@Param("now") LocalDateTime now, @Param("limit") int limit);
}
