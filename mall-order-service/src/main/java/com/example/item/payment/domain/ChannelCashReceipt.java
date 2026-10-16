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
@TableName("t_channel_cash_receipt")
public class ChannelCashReceipt {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String receiptNo;
    private String source;
    private String accountId;
    private String settlementNo;
    private String transferNo;
    private BigDecimal amount;
    private String currency;
    private LocalDate valueDate;
    private LocalDateTime observedAt;
    private String rawDetails;
    private LocalDateTime createdTime;
}
