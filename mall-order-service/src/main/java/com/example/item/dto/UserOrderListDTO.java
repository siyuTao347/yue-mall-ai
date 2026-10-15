package com.example.item.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserOrderListDTO {
    private String orderNo;
    private Integer orderVersion;
    private String viewRole; // "BUYER" | "SELLER"
    private String source;   // "NORMAL" | "SECKILL" | "LEGACY"

    private Long id;
    private Long buyerId;
    private Long sellerId;
    private Long merchantId;
    private Long itemId;
    private Integer quantity;

    private String unitPrice;
    private String orderAmount;
    private String paidAmount;
    private String refundedAmount;
    private String feeAmount;
    private String sellerIncome;
    private String currency;
    private Boolean priceConfirmed;

    private String orderStatus;
    private String payStatus;
    private String deliveryStatus;
    private String escrowStatus;
    private String disputeStatus;

    private String statusGroup;       // WAIT_PAY, PROCESSING, WAIT_DELIVERY, WAIT_CONFIRM, COMPLETED, AFTER_SALE, CLOSED
    private String displayStatus;     // Display status code
    private String statusDescription; // Description for users

    private String paymentNo;
    private List<String> allowedActions;

    private LocalDateTime payDeadline;
    private LocalDateTime deliveryDeadline;
    private LocalDateTime deliveredTime;
    private LocalDateTime confirmedTime;
    private LocalDateTime autoConfirmTime;
    private LocalDateTime settleAvailableTime;
    private LocalDateTime settledTime;

    private String itemSnapshot;
    private ItemDetail item;
    private Counterparty counterparty;

    private LocalDateTime createdTime;
    private LocalDateTime updatedTime;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ItemDetail {
        private String itemId;
        private String name;
        private String imageUrl;
        private String deliveryMode;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Counterparty {
        private String displayName;
        private Long id;
    }
}
