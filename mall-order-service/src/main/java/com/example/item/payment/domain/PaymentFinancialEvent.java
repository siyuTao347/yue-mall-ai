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
@TableName("t_payment_financial_event")
public class PaymentFinancialEvent {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String eventNo;
    private String profileId;
    private Integer profileVersion;
    private String channelMerchantNo;
    private String accountId;
    private String eventType;
    private String eventStage;
    private String bizType;
    private String bizNo;
    private String orderNo;
    private BigDecimal amount;
    private BigDecimal feeAmount;
    private String currency;
    private LocalDate channelBusinessDate;
    private LocalDateTime occurredAt;
    private LocalDateTime recordedAt;
    private LocalDateTime createdTime;
}
