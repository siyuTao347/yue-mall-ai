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
@TableName("t_mock_channel_posting")
public class MockChannelPosting {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String accountId;
    private Long sequenceNo;
    private String entryType;
    private String sourceEventNo;
    private BigDecimal amount;
    private String direction;
    private BigDecimal balanceAfter;
    private LocalDate channelBusinessDate;
    private LocalDateTime occurredAt;
    private LocalDateTime createdTime;
}
