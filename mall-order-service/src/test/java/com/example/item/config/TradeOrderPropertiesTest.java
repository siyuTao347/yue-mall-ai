package com.example.item.config;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TradeOrderPropertiesTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void defaultsAreValidAndMatchLegacyValues() {
        TradeOrderProperties properties = TradeOrderProperties.defaults();

        assertTrue(validator.validate(properties).isEmpty());
        assertEquals(new BigDecimal("2"), properties.feeRatePercent());
        assertEquals(new BigDecimal("0.01"), properties.minFee());
        assertEquals(15, properties.paymentExpireMinutes());
        assertEquals(30, properties.deliveryTimeoutMinutes());
        assertEquals(24, properties.autoConfirmHours());
        assertEquals(24, properties.settleCooldownHours());
    }

    @Test
    void outOfRangeValuesFailValidationAtStartup() {
        TradeOrderProperties properties = new TradeOrderProperties(
                new BigDecimal("200"), new BigDecimal("0.001"), 0, 2000, 24, 24);

        Set<ConstraintViolation<TradeOrderProperties>> violations = validator.validate(properties);

        assertFalse(violations.isEmpty());
        assertEquals(4, violations.size());
    }
}
