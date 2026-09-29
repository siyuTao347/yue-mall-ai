package com.example.item.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.item.entity.CardSecret;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface CardSecretMapper extends BaseMapper<CardSecret> {
    @Update("UPDATE t_card_secret SET status = 'LOCKED', order_no = #{orderNo}, " +
            "reservation_no = #{reservationNo}, locked_time = #{now}, updated_time = #{now} " +
            "WHERE id = #{id} AND status = 'AVAILABLE'")
    int lockById(@Param("id") Long id, @Param("orderNo") String orderNo,
                 @Param("reservationNo") String reservationNo, @Param("now") LocalDateTime now);

    @Update("UPDATE t_item i SET i.stock = i.stock - #{count}, i.frozen_stock = i.frozen_stock + #{count} " +
            "WHERE i.id = #{itemId} AND i.stock >= #{count}")
    int reserveStock(@Param("itemId") Long itemId, @Param("count") Integer count);

    @Update("UPDATE t_item i SET i.stock = i.stock + #{count}, " +
            "i.frozen_stock = i.frozen_stock - #{count} " +
            "WHERE i.id = #{itemId} AND i.frozen_stock >= #{count}")
    int releaseStock(@Param("itemId") Long itemId, @Param("count") Integer count);

    @Update("UPDATE t_item i SET i.frozen_stock = i.frozen_stock - #{count} " +
            "WHERE i.id = #{itemId} AND i.frozen_stock >= #{count}")
    int confirmStock(@Param("itemId") Long itemId, @Param("count") Integer count);

    @Update("UPDATE t_card_secret SET status = 'RELEASED', order_no = NULL, reservation_no = NULL, " +
            "locked_time = NULL, updated_time = #{now} WHERE reservation_no = #{reservationNo} AND status = 'LOCKED'")
    int releaseByReservation(@Param("reservationNo") String reservationNo, @Param("now") LocalDateTime now);

    @Update("UPDATE t_card_secret SET status = 'SOLD', sold_time = #{now}, updated_time = #{now} " +
            "WHERE reservation_no = #{reservationNo} AND status = 'LOCKED'")
    int markSoldByReservation(@Param("reservationNo") String reservationNo, @Param("now") LocalDateTime now);

    @Update("UPDATE t_card_secret SET status = 'INVALID', updated_time = #{now} " +
            "WHERE order_no = #{orderNo} AND status = 'SOLD'")
    int invalidateByOrderNo(@Param("orderNo") String orderNo, @Param("now") LocalDateTime now);
}
