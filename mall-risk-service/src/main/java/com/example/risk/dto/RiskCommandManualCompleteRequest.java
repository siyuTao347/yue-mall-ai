package com.example.risk.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RiskCommandManualCompleteRequest(
        @NotBlank String expectedCommandStatus,
        @NotBlank @Pattern(regexp = "SUCCESS|FAILED") String result,
        @NotBlank @Size(min = 10, max = 255) String reason,
        @Size(max = 512) String evidence
) {
}
