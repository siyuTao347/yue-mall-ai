package com.example.user.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class UserAccountBalanceDTO {
    private Long userId;
    private BigDecimal availableAmount;
    private BigDecimal pendingSettleAmount;
    private BigDecimal frozenAmount;
    private BigDecimal flowAvailableAmount;
    private BigDecimal flowPendingSettleAmount;
    private BigDecimal flowFrozenAmount;
}
