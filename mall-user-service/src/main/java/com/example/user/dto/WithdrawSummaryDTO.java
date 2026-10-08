package com.example.user.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record WithdrawSummaryDTO(
        Long id,
        String withdrawNo,
        Long merchantId,
        Long userId,
        BigDecimal amount,
        String maskedAccount,
        String status,
        LocalDateTime createdTime,
        LocalDateTime updatedTime
) {
}
