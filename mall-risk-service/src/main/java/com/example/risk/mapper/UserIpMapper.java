package com.example.risk.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.risk.entity.UserIp;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

@Mapper
public interface UserIpMapper extends BaseMapper<UserIp> {
    @Insert("INSERT INTO t_user_ip(user_id, ip_hash, first_seen_time, last_seen_time) " +
            "VALUES(#{userId}, #{ipHash}, #{now}, #{now}) ON DUPLICATE KEY UPDATE " +
            "hit_count = hit_count + 1, last_seen_time = VALUES(last_seen_time)")
    int upsert(@Param("userId") Long userId, @Param("ipHash") String ipHash,
               @Param("now") LocalDateTime now);
}
