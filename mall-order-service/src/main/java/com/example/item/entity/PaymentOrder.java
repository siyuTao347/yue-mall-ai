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
@TableName("t_payment_order")
public class PaymentOrder {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String paymentNo;
    private String orderNo;
    private String channel;
    private BigDecimal amount;
    private String status;
    private String callbackTokenHash;
    private Integer callbackSecretVersion;
    private LocalDateTime expireTime;
    private LocalDateTime createdTime;
    private LocalDateTime updatedTime;
}
