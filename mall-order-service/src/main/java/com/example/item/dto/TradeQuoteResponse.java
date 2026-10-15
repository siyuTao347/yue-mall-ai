package com.example.item.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TradeQuoteResponse {
    private String quoteNo;
    private Long itemId;
    private String itemName;
    private Integer quantity;
    private String unitPrice;
    private String orderAmount;
    private String currency;
    private String deliveryMode;
    private LocalDateTime expiresAt;
}
