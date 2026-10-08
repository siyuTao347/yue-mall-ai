package com.example.risk.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.Map;

public record RiskCaseResolveRequest(
        @NotBlank String expectedStatus,
        @NotNull @PositiveOrZero Long operationVersion,
        @NotBlank @Size(min = 2, max = 32) String command,
        @NotBlank @Size(min = 10, max = 255) String reason,
        Map<String, Object> actionParams
) {
}
