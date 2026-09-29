package com.example.item.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.item.entity.DeliveryRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface DeliveryRecordMapper extends BaseMapper<DeliveryRecord> {
    @Update("UPDATE t_delivery_record SET first_view_time = #{now}, status = 'VIEWED' " +
            "WHERE order_no = #{orderNo} AND first_view_time IS NULL")
    int markViewed(@Param("orderNo") String orderNo, @Param("now") LocalDateTime now);

    @Update("UPDATE t_delivery_record SET status = 'CONFIRMED', confirmed_time = #{now} " +
            "WHERE order_no = #{orderNo}")
    int confirmDelivery(@Param("orderNo") String orderNo, @Param("now") LocalDateTime now);
}
