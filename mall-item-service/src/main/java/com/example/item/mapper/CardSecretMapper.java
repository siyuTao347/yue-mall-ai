package com.example.item.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.item.entity.CardSecret;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Collection;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface CardSecretMapper extends BaseMapper<CardSecret> {
    @Select("""
            <script>
            SELECT secret_hash FROM t_card_secret
            WHERE item_id = #{itemId} AND secret_hash IN
            <foreach collection="hashes" item="hash" open="(" separator="," close=")">#{hash}</foreach>
            </script>
            """)
    List<String> selectExistingHashes(@Param("itemId") Long itemId,
                                      @Param("hashes") Collection<String> hashes);

    @Insert("""
            <script>
            INSERT INTO t_card_secret(
                item_id, merchant_id, secret_cipher, secret_hash, secret_mask,
                status, created_time, updated_time
            ) VALUES
            <foreach collection="cards" item="card" separator=",">
            (#{card.itemId}, #{card.merchantId}, #{card.secretCipher}, #{card.secretHash},
             #{card.secretMask}, #{card.status}, #{card.createdTime}, #{card.updatedTime})
            </foreach>
            </script>
            """)
    int batchInsert(@Param("cards") List<CardSecret> cards);

    @Select("SELECT id FROM t_card_secret WHERE item_id = #{itemId} AND status = 'AVAILABLE' " +
            "ORDER BY id LIMIT #{quantity} FOR UPDATE SKIP LOCKED")
    List<CardSecret> lockAvailableCards(@Param("itemId") Long itemId, @Param("quantity") Integer quantity);

    @Update("""
            <script>
            UPDATE t_card_secret SET status = 'LOCKED', order_no = #{orderNo},
            reservation_no = #{reservationNo}, locked_time = #{now}, updated_time = #{now}
            WHERE id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            AND status = 'AVAILABLE'
            </script>
            """)
    int lockByIds(@Param("ids") Collection<Long> ids, @Param("orderNo") String orderNo,
                  @Param("reservationNo") String reservationNo, @Param("now") LocalDateTime now);

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

    @Update("UPDATE t_card_secret SET status = 'RELEASED', order_no = NULL, reservation_no = NULL, " +
            "locked_time = NULL, updated_time = #{now} WHERE order_no = #{orderNo} " +
            "AND reservation_no = #{reservationNo} AND status = 'LOCKED'")
    int releaseByOrderAndReservation(@Param("orderNo") String orderNo,
                                     @Param("reservationNo") String reservationNo,
                                     @Param("now") LocalDateTime now);

    @Update("UPDATE t_card_secret SET status = 'SOLD', sold_time = #{now}, updated_time = #{now} " +
            "WHERE reservation_no = #{reservationNo} AND status = 'LOCKED'")
    int markSoldByReservation(@Param("reservationNo") String reservationNo, @Param("now") LocalDateTime now);

    @Update("UPDATE t_card_secret SET status = 'INVALID', updated_time = #{now} " +
            "WHERE order_no = #{orderNo} AND status = 'SOLD'")
    int invalidateByOrderNo(@Param("orderNo") String orderNo, @Param("now") LocalDateTime now);
}
