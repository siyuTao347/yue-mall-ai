package com.example.user.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class PlatformAccountBalanceDTO {
    private BigDecimal escrowAmount;
    private BigDecimal revenueAmount;
    private BigDecimal withdrawPendingAmount;
    private BigDecimal depositAmount;
    private BigDecimal flowEscrowAmount;
    private BigDecimal flowRevenueAmount;
    private BigDecimal flowWithdrawPendingAmount;
    private BigDecimal flowDepositAmount;
}
