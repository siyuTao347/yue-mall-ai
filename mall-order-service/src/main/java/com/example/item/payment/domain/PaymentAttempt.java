package com.example.item.payment.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_payment_attempt")
public class PaymentAttempt {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String attemptNo;
    private String paymentNo;
    private String orderNo;
    private Integer attemptSeq;
    private String paymentMethod;
    private String profileId;
    private Integer profileVersion;
    private String profileSnapshot;
    private String channelMerchantNo;
    private String channelTradeNo;
    private String outTradeNo;
    private BigDecimal amount;
    private String currency;
    private String status;
    private LocalDateTime channelSuccessAt;
    private LocalDate channelBusinessDate;
    private LocalDateTime localConfirmedAt;
    private String requestKey;
    private String requestHash;
    private LocalDateTime expiresAt;
    private LocalDateTime nextQueryAt;
    private String failureCode;
    private String allocationStatus;
    private Long version;
    private LocalDateTime createdTime;
    private LocalDateTime updatedTime;
}
