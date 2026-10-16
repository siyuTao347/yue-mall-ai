package com.example.item.payment.channel;

public record ChannelKey(
        String providerCode,
        String productCode,
        String mode
) {
    public ChannelKey {
        if (providerCode == null || productCode == null || mode == null) {
            throw new IllegalArgumentException("ChannelKey fields must not be null");
        }
    }

    public static ChannelKey ofMock(String providerCode, String productCode) {
        return new ChannelKey(providerCode, productCode, "MOCK");
    }
}
