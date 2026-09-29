package com.example.item.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_seckill_session")
public class SeckillSession {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String sessionName;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    /**
     * 0-预告/预热中, 1-进行中, 2-已下线/已结束
     */
    private Integer status;
    private LocalDateTime createTime;
}
