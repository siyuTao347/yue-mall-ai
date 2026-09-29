package com.example.item.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class ReconciliationDiffDTO {
    private String bizNo;
    private BigDecimal expectedAmount;
    private BigDecimal actualAmount;
}
