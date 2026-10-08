package com.example.user.dto;

import java.time.LocalDateTime;

public record MerchantSummaryDTO(
        Long id,
        Long userId,
        String merchantName,
        String status,
        Integer level,
        String riskStatus,
        String riskLevel,
        LocalDateTime createdTime,
        LocalDateTime updatedTime
) {
}
