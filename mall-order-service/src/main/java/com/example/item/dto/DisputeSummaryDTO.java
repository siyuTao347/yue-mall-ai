package com.example.item.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record DisputeSummaryDTO(
        Long id,
        String disputeNo,
        String orderNo,
        Long buyerId,
        Long sellerId,
        String disputeType,
        String reason,
        BigDecimal proposedRefundAmount,
        String status,
        LocalDateTime deadlineTime,
        LocalDateTime createdTime,
        LocalDateTime updatedTime
) {
}
