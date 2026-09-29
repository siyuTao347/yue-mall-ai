package com.example.risk.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_user_device")
public class UserDevice {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String deviceHash;
    private LocalDateTime firstSeenTime;
    private LocalDateTime lastSeenTime;
    private Integer hitCount;
}
