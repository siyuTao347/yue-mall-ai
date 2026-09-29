package com.example.item.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_seckill_item")
public class SeckillItem {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long sessionId;
    private Long itemId;
    private BigDecimal seckillPrice;
    private Integer seckillStock;
    private Integer remainStock;
    private Integer limitPerUser;
    private Integer sortOrder;
    private LocalDateTime createTime;
}
