package com.example.item.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record PaymentOrderResponse(
        String paymentNo,
        String orderNo,
        BigDecimal amount,
        String status,
        LocalDateTime expireTime
) {
}
