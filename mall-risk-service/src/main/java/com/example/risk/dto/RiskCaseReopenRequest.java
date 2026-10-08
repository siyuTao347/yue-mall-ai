package com.example.risk.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record RiskCaseReopenRequest(
        @NotBlank String expectedStatus,
        @NotBlank String expectedCommandStatus,
        @NotNull @PositiveOrZero Long operationVersion,
        @NotBlank @Size(min = 10, max = 255) String reason
) {
}
