package com.example.risk.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record RiskCaseAssignRequest(
        @NotBlank String expectedStatus,
        @NotNull @PositiveOrZero Long operationVersion
) {
}
