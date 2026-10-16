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
@TableName("t_mock_statement")
public class MockStatement {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String scopeId;
    private String profileId;
    private String channelMerchantNo;
    private String accountId;
    private String statementType;
    private LocalDate businessDate;
    private Integer version;
    private String fileHash;
    private Integer rowCount;
    private BigDecimal totalPayAmount;
    private BigDecimal totalRefundAmount;
    private BigDecimal totalFeeAmount;
    private BigDecimal totalFeeReturnAmount;
    private BigDecimal netAmount;
    private LocalDateTime availableAt;
    private LocalDateTime generatedAt;
    private String fileLocation;
    private String fileContent;
    private LocalDateTime createdTime;
}
