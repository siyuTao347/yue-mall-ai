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
@TableName("t_mock_settlement_transfer")
public class MockSettlementTransfer {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String mockSettlementNo;
    private String profileId;
    private String channelMerchantNo;
    private LocalDate businessDate;
    private String targetAccount;
    private Integer version;
    private BigDecimal netAmount;
    private LocalDateTime plannedTime;
    private LocalDateTime actualTime;
    private String status;
    private LocalDateTime createdTime;
}
