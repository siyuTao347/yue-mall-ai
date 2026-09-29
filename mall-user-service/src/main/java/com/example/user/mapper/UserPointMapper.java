package com.example.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.user.entity.UserPoint;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface UserPointMapper extends BaseMapper<UserPoint> {
    @Update("UPDATE t_user_point SET total_points = total_points + #{points}, " +
            "history_earned_points = history_earned_points + #{points}, " +
            "version = version + 1 " +
            "WHERE user_id = #{userId}")
    int addPoints(@Param("userId") Long userId, @Param("points") Integer points);
}
