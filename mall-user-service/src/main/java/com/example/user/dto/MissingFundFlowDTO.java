package com.example.user.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class MissingFundFlowDTO {
    private String transactionNo;
    private BigDecimal amount;
}
