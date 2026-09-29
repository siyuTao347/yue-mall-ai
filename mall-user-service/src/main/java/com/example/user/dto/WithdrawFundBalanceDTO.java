package com.example.user.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class WithdrawFundBalanceDTO {
    private String withdrawNo;
    private String status;
    private BigDecimal amount;
    private BigDecimal freezeAmount;
    private BigDecimal payoutAmount;
    private BigDecimal rejectAmount;
}
