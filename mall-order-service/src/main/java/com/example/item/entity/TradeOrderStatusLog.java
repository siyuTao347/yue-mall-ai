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
@TableName("t_trade_order_status_log")
public class TradeOrderStatusLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String orderNo;
    private String fromStatus;
    private String toStatus;
    private String statusType;
    private String operatorType;
    private Long operatorId;
    private String reason;
    private LocalDateTime createdTime;
}
