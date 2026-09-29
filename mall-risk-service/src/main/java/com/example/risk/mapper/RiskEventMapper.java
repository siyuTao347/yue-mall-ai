package com.example.risk.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.risk.entity.RiskEvent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Mapper
public interface RiskEventMapper extends BaseMapper<RiskEvent> {
    @Update("UPDATE t_risk_event SET event_phase = 'CONFIRMED', confirmed_time = #{now}, " +
            "updated_time = #{now} WHERE event_no = #{eventNo} AND event_phase = 'PRECHECK'")
    int confirmEvent(@Param("eventNo") String eventNo, @Param("now") LocalDateTime now);

    @Update("UPDATE t_risk_event SET event_phase = 'INVALID', invalidated_time = #{now}, " +
            "updated_time = #{now} WHERE event_phase = 'PRECHECK' AND occurred_time < #{expireBefore}")
    int invalidateExpired(@Param("expireBefore") LocalDateTime expireBefore, @Param("now") LocalDateTime now);

    @Select("SELECT COUNT(*) FROM t_risk_event WHERE event_phase = 'CONFIRMED' " +
            "AND scene = #{scene} AND user_id = #{userId} AND occurred_time >= #{fromTime}")
    long countUserEvents(@Param("scene") String scene, @Param("userId") Long userId,
                         @Param("fromTime") LocalDateTime fromTime);

    @Select("SELECT COUNT(*) FROM t_risk_event WHERE event_phase = 'CONFIRMED' " +
            "AND scene = #{scene} AND event_type = #{eventType} AND user_id = #{userId} " +
            "AND occurred_time >= #{fromTime}")
    long countUserTypedEvents(@Param("scene") String scene, @Param("eventType") String eventType,
                              @Param("userId") Long userId, @Param("fromTime") LocalDateTime fromTime);

    @Select("SELECT COUNT(*) FROM t_risk_event WHERE event_phase = 'CONFIRMED' " +
            "AND scene = #{scene} AND event_type = #{eventType} AND user_id = #{userId}")
    long countUserTotalTypedEvents(@Param("scene") String scene, @Param("eventType") String eventType,
                                   @Param("userId") Long userId);

    @Select("SELECT COUNT(*) FROM t_risk_event WHERE event_phase = 'CONFIRMED' " +
            "AND scene = #{scene} AND event_type = #{eventType} AND merchant_id = #{merchantId} " +
            "AND occurred_time >= #{fromTime}")
    long countMerchantTypedEvents(@Param("scene") String scene, @Param("eventType") String eventType,
                                  @Param("merchantId") Long merchantId,
                                  @Param("fromTime") LocalDateTime fromTime);

    @Select("SELECT COUNT(*) FROM t_risk_event WHERE event_phase = 'CONFIRMED' " +
            "AND scene = #{scene} AND event_type = #{eventType} AND merchant_id = #{merchantId}")
    long countMerchantTotalTypedEvents(@Param("scene") String scene, @Param("eventType") String eventType,
                                       @Param("merchantId") Long merchantId);

    @Select("SELECT COUNT(*) FROM t_risk_event WHERE event_phase = 'CONFIRMED' " +
            "AND scene = #{scene} AND ip_hash = #{ipHash} AND occurred_time >= #{fromTime}")
    long countIpEvents(@Param("scene") String scene, @Param("ipHash") String ipHash,
                       @Param("fromTime") LocalDateTime fromTime);

    @Select("SELECT COUNT(*) FROM t_risk_event WHERE event_phase = 'CONFIRMED' " +
            "AND scene = #{scene} AND device_hash = #{deviceHash} AND occurred_time >= #{fromTime}")
    long countDeviceEvents(@Param("scene") String scene, @Param("deviceHash") String deviceHash,
                           @Param("fromTime") LocalDateTime fromTime);

    @Select("SELECT COALESCE(SUM(amount), 0) FROM t_risk_event WHERE event_phase = 'CONFIRMED' " +
            "AND scene = 'WITHDRAW' AND merchant_id = #{merchantId} AND occurred_time >= #{fromTime}")
    BigDecimal sumMerchantWithdraw(@Param("merchantId") Long merchantId,
                                   @Param("fromTime") LocalDateTime fromTime);

    @Select("SELECT COUNT(DISTINCT merchant_id) FROM t_risk_event WHERE event_phase = 'CONFIRMED' " +
            "AND scene = 'WITHDRAW' AND JSON_UNQUOTE(JSON_EXTRACT(payload_json, '$.withdrawAccountHash')) = " +
            "#{accountHash} AND occurred_time >= #{fromTime}")
    long countWithdrawAccountMerchants(@Param("accountHash") String accountHash,
                                        @Param("fromTime") LocalDateTime fromTime);
}
