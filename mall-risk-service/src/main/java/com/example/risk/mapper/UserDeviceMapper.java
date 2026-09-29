package com.example.risk.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.risk.entity.UserDevice;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

@Mapper
public interface UserDeviceMapper extends BaseMapper<UserDevice> {
    @Insert("INSERT INTO t_user_device(user_id, device_hash, first_seen_time, last_seen_time) " +
            "VALUES(#{userId}, #{deviceHash}, #{now}, #{now}) ON DUPLICATE KEY UPDATE " +
            "hit_count = hit_count + 1, last_seen_time = VALUES(last_seen_time)")
    int upsert(@Param("userId") Long userId, @Param("deviceHash") String deviceHash,
               @Param("now") LocalDateTime now);
}
