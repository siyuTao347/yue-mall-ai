package com.example.item.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

@ConfigurationProperties(prefix = "trade.payment-callback")
public record PaymentCallbackProperties(
        String secret,
        int secretVersion,
        String previousSecret,
        Integer previousSecretVersion,
        long clockSkewSeconds,
        long nonceTtlSeconds,
        boolean mockEnabled
) {

    public PaymentCallbackProperties {
        if (!StringUtils.hasText(secret)) {
            throw new IllegalArgumentException("trade.payment-callback.secret is required");
        }
        if (secretVersion <= 0) {
            secretVersion = 1;
        }
        previousSecret = StringUtils.hasText(previousSecret) ? previousSecret : null;
        previousSecretVersion = previousSecretVersion == null || previousSecretVersion <= 0
                ? null
                : previousSecretVersion;
        if ((previousSecret == null) != (previousSecretVersion == null)) {
            throw new IllegalArgumentException(
                    "trade.payment-callback.previous-secret and previous-secret-version must be configured together");
        }
        if (previousSecretVersion != null && previousSecretVersion >= secretVersion) {
            throw new IllegalArgumentException("previous secret version must be less than current secret version");
        }
        clockSkewSeconds = clockSkewSeconds <= 0 ? 300 : clockSkewSeconds;
        nonceTtlSeconds = nonceTtlSeconds <= 0 ? 900 : nonceTtlSeconds;
    }
}
