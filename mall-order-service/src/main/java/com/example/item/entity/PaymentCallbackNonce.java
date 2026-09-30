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
@TableName("t_payment_callback_nonce")
public class PaymentCallbackNonce {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String nonce;
    private String paymentNo;
    private Integer secretVersion;
    private LocalDateTime expireTime;
    private LocalDateTime createdTime;
}
