package com.example.risk.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;

@Mapper
public interface RiskIdentityMapper {
    @Select("""
            SELECT CASE
              WHEN EXISTS(SELECT 1 FROM t_user_device WHERE device_hash = #{deviceHash}
                AND user_id = #{sellerId} AND last_seen_time >= #{fromTime}) THEN 'SAME_DEVICE'
              WHEN EXISTS(SELECT 1 FROM t_user_ip WHERE ip_hash = #{ipHash}
                AND user_id = #{sellerId} AND last_seen_time >= #{fromTime}) THEN 'SAME_IP'
              ELSE 'NONE'
            END
            """)
    String sameDeviceOrIp(@Param("sellerId") Long sellerId,
                          @Param("deviceHash") String deviceHash,
                          @Param("ipHash") String ipHash,
                          @Param("fromTime") LocalDateTime fromTime);

    @Select("SELECT EXISTS(SELECT 1 FROM t_relation_edge WHERE (source_user_id = #{leftUserId} " +
            "AND target_user_id = #{rightUserId}) OR (source_user_id = #{rightUserId} " +
            "AND target_user_id = #{leftUserId}))")
    boolean existsEdge(@Param("leftUserId") Long leftUserId, @Param("rightUserId") Long rightUserId);
}
