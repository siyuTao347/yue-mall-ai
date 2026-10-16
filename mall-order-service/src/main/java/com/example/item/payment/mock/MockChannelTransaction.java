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
@TableName("t_mock_channel_transaction")
public class MockChannelTransaction {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String profileId;
    private String channelMerchantNo;
    private String outTradeNo;
    private String channelTradeNo;
    private String paymentMethod;
    private BigDecimal amount;
    private BigDecimal feeAmount;
    private String currency;
    private String status;
    private LocalDateTime channelSuccessAt;
    private LocalDate channelBusinessDate;
    private String faultPlanId;
    private String rawDetails;
    private LocalDateTime createdTime;
    private LocalDateTime updatedTime;
}
