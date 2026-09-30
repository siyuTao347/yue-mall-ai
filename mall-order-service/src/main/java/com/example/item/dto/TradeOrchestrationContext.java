package com.example.item.dto;

import java.util.Map;
import java.math.BigDecimal;

public record TradeOrchestrationContext(
        String orderNo,
        String paymentNo,
        String disputeNo,
        String result,
        String reason,
        Long adminId,
        Long buyerId,
        Long sellerId,
        Long merchantId,
        BigDecimal orderAmount,
        BigDecimal feeAmount,
        BigDecimal sellerIncome,
        String fundTransactionNo,
        Long itemId,
        Integer quantity,
        String eventNo,
        String scene,
        String eventType,
        String ipHash,
        String deviceHash,
        Map<String, Object> payload
) {
    public static TradeOrchestrationContext empty() {
        return new TradeOrchestrationContext(
                null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null);
    }

    public TradeOrchestrationContext(
            String orderNo,
            String paymentNo,
            String disputeNo,
            String result,
            String reason,
            Long adminId,
            Long buyerId,
            Long sellerId,
            Long merchantId,
            BigDecimal orderAmount,
            BigDecimal feeAmount,
            BigDecimal sellerIncome,
            String fundTransactionNo
    ) {
        this(orderNo, paymentNo, disputeNo, result, reason, adminId, buyerId, sellerId, merchantId,
                orderAmount, feeAmount, sellerIncome, fundTransactionNo,
                null, null, null, null, null, null, null, null);
    }

    public TradeOrchestrationContext withSettlementAmounts(BigDecimal feeAmount, BigDecimal sellerIncome) {
        return new TradeOrchestrationContext(
                orderNo, paymentNo, disputeNo, result, reason, adminId, buyerId, sellerId, merchantId,
                orderAmount, feeAmount, sellerIncome, fundTransactionNo,
                itemId, quantity, eventNo, scene, eventType, ipHash, deviceHash, payload);
    }

    public TradeOrchestrationContext withFundTransactionNo(String fundTransactionNo) {
        return new TradeOrchestrationContext(
                orderNo, paymentNo, disputeNo, result, reason, adminId, buyerId, sellerId, merchantId,
                orderAmount, feeAmount, sellerIncome, fundTransactionNo,
                itemId, quantity, eventNo, scene, eventType, ipHash, deviceHash, payload);
    }
}
