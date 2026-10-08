package com.example.risk.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class MerchantMetricAggregate {
    private Long completedCount;
    private Long refundCount30d;
    private Long disputeCount30d;
    private BigDecimal withdrawAmount24h;
}
