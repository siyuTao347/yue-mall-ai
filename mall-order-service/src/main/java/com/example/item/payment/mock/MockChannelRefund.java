package com.example.item.payment.mock;

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
@TableName("t_mock_channel_refund")
public class MockChannelRefund {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String channelMerchantNo;
    private String refundNo;
    private String channelRefundNo;
    private String channelTradeNo;
    private BigDecimal amount;
    private BigDecimal feeReturnAmount;
    private String currency;
    private String status;
    private LocalDateTime acceptedAt;
    private LocalDateTime successAt;
    private LocalDate channelBusinessDate;
    private LocalDateTime createdTime;
    private LocalDateTime updatedTime;
}
