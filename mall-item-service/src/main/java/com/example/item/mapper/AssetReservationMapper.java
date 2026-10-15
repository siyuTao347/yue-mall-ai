package com.example.item.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.item.entity.AssetReservation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface AssetReservationMapper extends BaseMapper<AssetReservation> {
    @Update("UPDATE t_asset_reservation SET status = 'REFUNDED', updated_time = #{now} " +
            "WHERE order_no = #{orderNo} AND status = 'CONFIRMED'")
    int markRefundedByOrderNo(@Param("orderNo") String orderNo, @Param("now") LocalDateTime now);

    @Update("UPDATE t_asset_reservation SET status = 'RELEASED', updated_time = #{now} " +
            "WHERE reservation_no = #{reservationNo} AND status = 'RESERVED'")
    int markReleased(@Param("reservationNo") String reservationNo, @Param("now") LocalDateTime now);

    @Update("UPDATE t_asset_reservation SET status = 'CONFIRMED', updated_time = #{now} " +
            "WHERE reservation_no = #{reservationNo} AND status = 'RESERVED'")
    int markConfirmed(@Param("reservationNo") String reservationNo, @Param("now") LocalDateTime now);
}
