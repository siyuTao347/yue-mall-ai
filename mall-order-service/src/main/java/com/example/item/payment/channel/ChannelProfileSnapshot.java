package com.example.item.payment.channel;

import com.example.item.payment.domain.PaymentMethod;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

public record ChannelProfileSnapshot(
        String profileId,
        int version,
        PaymentMethod paymentMethod,
        String providerCode,
        String productCode,
        String channelMerchantNo,
        String accountId,
        String zoneId,
        String cutoffTime,
        BigDecimal feeRate,
        String settlementDayBasis,
        int settlementOffsetDays,
        String settlementTime,
        String settlementCalendarId,
        String settlementTarget,
        String statementAvailableTime
) {
    public static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Shanghai");

    public ZoneId getZone() {
        return zoneId != null && !zoneId.isBlank() ? ZoneId.of(zoneId) : DEFAULT_ZONE;
    }

    public LocalTime getCutoff() {
        return cutoffTime != null && !cutoffTime.isBlank() ? LocalTime.parse(cutoffTime) : LocalTime.MIDNIGHT;
    }

    public BigDecimal calculateFee(BigDecimal amount) {
        if (feeRate == null || feeRate.compareTo(BigDecimal.ZERO) <= 0 || amount == null) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return amount.multiply(feeRate).setScale(2, RoundingMode.HALF_UP);
    }

    public static ChannelProfileSnapshot defaultFor(PaymentMethod method) {
        return switch (method) {
            case UNIONPAY_BANK -> new ChannelProfileSnapshot(
                    "unionpay-demo-v1",
                    1,
                    PaymentMethod.UNIONPAY_BANK,
                    "UNIONPAY",
                    "B2C_EXPRESS",
                    "MERCH_UP_001",
                    "ACC_UP_SETTLE",
                    "Asia/Shanghai",
                    "23:00",
                    new BigDecimal("0.005"),
                    "BUSINESS",
                    1,
                    "09:30",
                    "MOCK_CN_ACQUIRER",
                    "BANK",
                    "09:00"
            );
            case WECHAT_PAY -> new ChannelProfileSnapshot(
                    "wechat-demo-v1",
                    1,
                    PaymentMethod.WECHAT_PAY,
                    "WECHAT",
                    "NATIVE_APP",
                    "MERCH_WX_001",
                    "ACC_WX_BASIC",
                    "Asia/Shanghai",
                    "00:00",
                    new BigDecimal("0.006"),
                    "CALENDAR",
                    1,
                    "11:00",
                    null,
                    "BANK",
                    "10:00"
            );
            case ALIPAY -> new ChannelProfileSnapshot(
                    "alipay-demo-v1",
                    1,
                    PaymentMethod.ALIPAY,
                    "ALIPAY",
                    "DIRECT_PAY",
                    "MERCH_ALI_001",
                    "ACC_ALI_BASIC",
                    "Asia/Shanghai",
                    "00:00",
                    new BigDecimal("0.006"),
                    "CALENDAR",
                    1,
                    "09:00",
                    null,
                    "BANK",
                    "08:00"
            );
            case ECNY -> new ChannelProfileSnapshot(
                    "ecny-demo-v1",
                    1,
                    PaymentMethod.ECNY,
                    "PBOC_ECNY",
                    "WALLET_PAY",
                    "MERCH_ECNY_001",
                    "ACC_ECNY_WALLET_01",
                    "Asia/Shanghai",
                    "00:00",
                    BigDecimal.ZERO,
                    "REALTIME",
                    0,
                    "00:00",
                    null,
                    "ECNY_WALLET",
                    "00:10"
            );
        };
    }
}
