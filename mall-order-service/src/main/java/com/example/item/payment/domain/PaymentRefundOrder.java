package com.example.item.payment.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_payment_refund_order")
public class PaymentRefundOrder {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String refundNo;
    private String originalAttemptNo;
    private String orderNo;
    private BigDecimal amount;
    private String currency;
    private String reason;
    private String status;
    private String channelRefundNo;
    private String fundTaskNo;
    private String requestKey;
    private LocalDateTime acceptedAt;
    private LocalDateTime successAt;
    private LocalDateTime nextQueryAt;
    private String failureCode;
    private String failureReason;
    private Long version;
    private LocalDateTime createdTime;
    private LocalDateTime updatedTime;
}
