package com.example.item.reconciliation.domain;

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
@TableName("t_recon_channel_item")
public class ReconChannelItem {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long fileId;
    private Integer lineNo;
    private String itemType;
    private String outTradeNo;
    private String channelTradeNo;
    private String refundNo;
    private BigDecimal amount;
    private BigDecimal feeAmount;
    private String currency;
    private String status;
    private LocalDateTime channelTime;
    private String rawLine;
    private LocalDateTime createdTime;
}
