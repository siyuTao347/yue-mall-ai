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
@TableName("t_channel_settlement_line")
public class ChannelSettlementLine {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String settlementNo;
    private String financialEventNo;
    private String settlementPurpose;
    private String direction;
    private BigDecimal amount;
    private BigDecimal fee;
    private BigDecimal feeReturn;
    private BigDecimal expectedNet;
    private LocalDateTime createdTime;
}
