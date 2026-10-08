package com.example.item.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record TradeOrderSummaryDTO(
        Long id,
        String orderNo,
        Long buyerId,
        Long sellerId,
        Long merchantId,
        Long itemId,
        Integer quantity,
        BigDecimal orderAmount,
        BigDecimal feeAmount,
        BigDecimal sellerIncome,
        String orderStatus,
        String payStatus,
        String deliveryStatus,
        String escrowStatus,
        String disputeStatus,
        String paymentNo,
        LocalDateTime payDeadline,
        LocalDateTime deliveryDeadline,
        LocalDateTime deliveredTime,
        LocalDateTime confirmedTime,
        LocalDateTime autoConfirmTime,
        LocalDateTime settleAvailableTime,
        LocalDateTime settledTime,
        String itemSnapshot,
        LocalDateTime createdTime,
        LocalDateTime updatedTime
) {
}
