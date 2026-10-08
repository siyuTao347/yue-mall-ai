package com.example.user.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record FundFlowSummaryDTO(
        Long id,
        String transactionNo,
        String ownerType,
        Long ownerId,
        String accountType,
        String direction,
        BigDecimal amount,
        BigDecimal balanceAfter,
        LocalDateTime createdTime
) {
}
