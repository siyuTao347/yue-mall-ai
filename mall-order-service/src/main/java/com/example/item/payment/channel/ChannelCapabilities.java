package com.example.item.payment.channel;

public record ChannelCapabilities(
        boolean canQuery,
        boolean canClose,
        boolean canRefund,
        boolean canDailyStatement,
        boolean canIncrementalPosting,
        boolean canRedeem
) {
    public static ChannelCapabilities standard() {
        return new ChannelCapabilities(true, true, true, true, false, false);
    }

    public static ChannelCapabilities ecny() {
        return new ChannelCapabilities(true, true, true, true, true, true);
    }
}
