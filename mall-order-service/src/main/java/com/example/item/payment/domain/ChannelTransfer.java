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
@TableName("t_channel_transfer")
public class ChannelTransfer {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String transferNo;
    private String transferType;
    private String fromAccount;
    private String toAccount;
    private BigDecimal amount;
    private String currency;
    private String requestKey;
    private String status;
    private String channelTradeNo;
    private LocalDateTime createdTime;
    private LocalDateTime updatedTime;
}
