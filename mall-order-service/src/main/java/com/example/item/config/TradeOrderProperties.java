package com.example.item.config;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;

/**
 * 交易订单配置：费率、金额下限、支付/交付超时、自动确认与结算冷却。
 *
 * <p>前缀 {@code trade.order}，启动时校验非法配置，替代散落的 {@code @Value}。</p>
 */
@Validated
@ConfigurationProperties(prefix = "trade.order")
public record TradeOrderProperties(
        @DefaultValue("2") @DecimalMin("0") @DecimalMax("100") BigDecimal feeRatePercent,
        @DefaultValue("0.01") @DecimalMin("0.01") BigDecimal minFee,
        @DefaultValue("15") @Min(1) @Max(1440) int paymentExpireMinutes,
        @DefaultValue("30") @Min(1) @Max(1440) int deliveryTimeoutMinutes,
        @DefaultValue("24") @Min(1) @Max(720) int autoConfirmHours,
        @DefaultValue("24") @Min(0) @Max(720) int settleCooldownHours
) {

    /**
     * 默认值：与历史 {@code @Value} 默认值保持一致，供测试与旧调用方使用。
     */
    public static TradeOrderProperties defaults() {
        return new TradeOrderProperties(
                new BigDecimal("2"), new BigDecimal("0.01"), 15, 30, 24, 24);
    }
}
