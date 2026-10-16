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
@TableName("t_channel_settlement_order")
public class ChannelSettlementOrder {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String settlementNo;
    private String profileId;
    private String channelMerchantNo;
    private String accountId;
    private LocalDate businessDate;
    private String settlementTarget;
    private String targetAccount;
    private Integer planVersion;
    private BigDecimal grossAmount;
    private BigDecimal refundAmount;
    private BigDecimal feeAmount;
    private BigDecimal feeReturnAmount;
    private BigDecimal netAmount;
    private String status;
    private LocalDateTime expectedSettledAt;
    private LocalDateTime actualSettledAt;
    private LocalDateTime createdTime;
    private LocalDateTime updatedTime;
}
