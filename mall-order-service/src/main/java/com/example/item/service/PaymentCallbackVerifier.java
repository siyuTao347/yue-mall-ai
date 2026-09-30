package com.example.item.service;

import com.example.item.config.PaymentCallbackProperties;
import com.example.item.dto.PaymentCallbackRequest;
import com.example.item.entity.PaymentOrder;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

@Service
public class PaymentCallbackVerifier {

    private static final String HMAC_SHA256 = "HmacSHA256";
    private static final String SHA_256 = "SHA-256";

    private final PaymentCallbackProperties properties;

    public PaymentCallbackVerifier(PaymentCallbackProperties properties) {
        this.properties = properties;
    }

    public boolean verify(PaymentCallbackRequest request, PaymentOrder payment) {
        String secret = resolveSecret(payment);
        if (secret == null) {
            return false;
        }
        String expected = hmacSha256(secret, canonicalText(request));
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII),
                request.signature().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII)
        );
    }

    public String sign(PaymentCallbackRequest request) {
        return hmacSha256(properties.secret(), canonicalText(request));
    }

    public String sign(PaymentCallbackRequest request, PaymentOrder payment) {
        String secret = resolveSecret(payment);
        return hmacSha256(secret == null ? properties.secret() : secret, canonicalText(request));
    }

    public int resolveSecretVersion(PaymentOrder payment) {
        Integer paymentVersion = payment.getCallbackSecretVersion();
        if (paymentVersion == null || paymentVersion == properties.secretVersion()) {
            return properties.secretVersion();
        }
        if (properties.previousSecretVersion() != null
                && paymentVersion.equals(properties.previousSecretVersion())) {
            return paymentVersion;
        }
        return properties.secretVersion();
    }

    public long clockSkewMillis() {
        return properties.clockSkewSeconds() * 1000L;
    }

    String canonicalText(PaymentCallbackRequest request) {
        return request.paymentNo() + "\n"
                + request.orderNo() + "\n"
                + normalizeAmount(request.amount()) + "\n"
                + request.result() + "\n"
                + request.timestamp() + "\n"
                + request.nonce() + "\n"
                + sha256Hex(request.rawPayload());
    }

    private String resolveSecret(PaymentOrder payment) {
        Integer paymentVersion = payment.getCallbackSecretVersion();
        if (paymentVersion == null || paymentVersion == properties.secretVersion()) {
            return properties.secret();
        }
        if (properties.previousSecretVersion() != null
                && paymentVersion.equals(properties.previousSecretVersion())) {
            return properties.previousSecret();
        }
        return null;
    }

    private String normalizeAmount(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance(SHA_256);
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to calculate payment callback digest", exception);
        }
    }

    private String hmacSha256(String secret, String canonicalText) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256));
            return HexFormat.of().formatHex(mac.doFinal(canonicalText.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to calculate payment callback signature", exception);
        }
    }
}
