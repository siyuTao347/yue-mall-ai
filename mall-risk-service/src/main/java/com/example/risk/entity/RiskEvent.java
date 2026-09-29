package com.example.risk.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("t_risk_event")
public class RiskEvent {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String eventNo;
    private String scene;
    private String eventType;
    private String eventPhase;
    private String bizType;
    private String bizNo;
    private Long userId;
    private Long merchantId;
    private Long itemId;
    private String orderNo;
    private String withdrawNo;
    private BigDecimal amount;
    private String ipHash;
    private String deviceHash;
    private String payloadJson;
    private String requestHash;
    private LocalDateTime occurredTime;
    private LocalDateTime confirmedTime;
    private LocalDateTime invalidatedTime;
    private LocalDateTime createdTime;
    private LocalDateTime updatedTime;
}
