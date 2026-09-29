package com.example.user.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_user_point")
public class UserPoint {
    @TableId
    private Long userId;
    private Integer totalPoints;
    private Integer historyEarnedPoints;
    private Integer version;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
