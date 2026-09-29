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
@TableName("t_payment_callback")
public class PaymentCallback {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String callbackNo;
    private String paymentNo;
    private String result;
    private BigDecimal amount;
    private String signature;
    private String rawPayload;
    private LocalDateTime receivedTime;
}
