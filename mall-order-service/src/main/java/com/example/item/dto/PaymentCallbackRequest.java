package com.example.item.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record PaymentCallbackRequest(
        @NotBlank
        @Size(max = 64)
        String callbackNo,

        @NotBlank
        @Size(max = 64)
        String paymentNo,

        @NotBlank
        @Size(max = 64)
        String orderNo,

        @NotNull
        @DecimalMin(value = "0.01")
        @Digits(integer = 16, fraction = 2)
        BigDecimal amount,

        @NotBlank
        @Pattern(regexp = "SUCCESS|FAIL")
        String result,

        @NotNull
        @Positive
        Long timestamp,

        @NotBlank
        @Size(min = 16, max = 64)
        @Pattern(regexp = "^[A-Za-z0-9_-]+$")
        String nonce,

        @NotBlank
        @Pattern(regexp = "^[0-9a-fA-F]{64}$")
        String signature,

        @NotBlank
        @Size(max = 8192)
        String rawPayload
) {
}
