package com.example.item.service;

import com.example.item.config.PaymentCallbackProperties;
import com.example.item.dto.PaymentCallbackRequest;
import com.example.item.entity.PaymentOrder;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

class PaymentCallbackVerifierTest {

    private final PaymentCallbackProperties properties = properties("current-secret", 2, null, null);
    private final PaymentCallbackVerifier verifier = new PaymentCallbackVerifier(properties);

    @Test
    void verifiesValidSignatureAndNormalizesAmount() {
        PaymentOrder payment = payment(2);
        PaymentCallbackRequest request = request(new BigDecimal("100"), "signature-placeholder");
        String signature = verifier.sign(request, payment);
        PaymentCallbackRequest signedRequest = withSignature(request, signature);

        Assertions.assertTrue(verifier.verify(signedRequest, payment));
    }

    @Test
    void acceptsUppercaseSignature() {
        PaymentOrder payment = payment(2);
        PaymentCallbackRequest request = signedRequest(payment);
        PaymentCallbackRequest uppercaseRequest = withSignature(request, request.signature().toUpperCase());

        Assertions.assertTrue(verifier.verify(uppercaseRequest, payment));
    }

    @Test
    void rejectsWrongSecret() {
        PaymentOrder payment = payment(2);
        PaymentCallbackRequest request = signedRequest(payment);
        PaymentCallbackVerifier wrongSecretVerifier =
                new PaymentCallbackVerifier(properties("wrong-secret", 2, null, null));

        Assertions.assertFalse(wrongSecretVerifier.verify(request, payment));
    }

    @Test
    void verifiesPreviousSecretOnlyForItsVersion() {
        PaymentCallbackProperties rotationProperties =
                properties("current-secret", 2, "previous-secret", 1);
        PaymentCallbackVerifier rotationVerifier = new PaymentCallbackVerifier(rotationProperties);
        PaymentOrder previousPayment = payment(1);
        PaymentCallbackRequest request = signedRequest(rotationVerifier, previousPayment);

        Assertions.assertTrue(rotationVerifier.verify(request, previousPayment));
        Assertions.assertFalse(verifier.verify(request, previousPayment));
    }

    @Test
    void rejectsTamperedSignedFields() {
        PaymentOrder payment = payment(2);
        PaymentCallbackRequest signedRequest = signedRequest(payment);

        Assertions.assertAll(
                () -> Assertions.assertFalse(verifier.verify(withPaymentNo(signedRequest, "PAY2"), payment)),
                () -> Assertions.assertFalse(verifier.verify(withOrderNo(signedRequest, "TR2"), payment)),
                () -> Assertions.assertFalse(verifier.verify(
                        withAmount(signedRequest, new BigDecimal("99.00")), payment)),
                () -> Assertions.assertFalse(verifier.verify(withResult(signedRequest, "FAIL"), payment)),
                () -> Assertions.assertFalse(verifier.verify(
                        withTimestamp(signedRequest, signedRequest.timestamp() + 1), payment)),
                () -> Assertions.assertFalse(verifier.verify(withNonce(signedRequest, "other-nonce"), payment)),
                () -> Assertions.assertFalse(verifier.verify(
                        withRawPayload(signedRequest, "{\"tampered\":true}"), payment))
        );
    }

    @Test
    void rejectsUnknownSecretVersion() {
        PaymentCallbackRequest request = signedRequest(payment(99));

        Assertions.assertFalse(verifier.verify(request, payment(99)));
    }

    private PaymentCallbackRequest signedRequest(PaymentOrder payment) {
        return signedRequest(verifier, payment);
    }

    private PaymentCallbackRequest signedRequest(PaymentCallbackVerifier callbackVerifier, PaymentOrder payment) {
        PaymentCallbackRequest unsignedRequest = request(payment.getAmount(), "signature-placeholder");
        return withSignature(unsignedRequest, callbackVerifier.sign(unsignedRequest, payment));
    }

    private PaymentCallbackRequest request(BigDecimal amount, String signature) {
        return new PaymentCallbackRequest(
                "CB1",
                "PAY1",
                "TR1",
                amount,
                "SUCCESS",
                System.currentTimeMillis(),
                "1234567890abcdef",
                signature,
                "{\"result\":\"SUCCESS\"}"
        );
    }

    private PaymentCallbackRequest withSignature(PaymentCallbackRequest source, String signature) {
        return new PaymentCallbackRequest(
                source.callbackNo(),
                source.paymentNo(),
                source.orderNo(),
                source.amount(),
                source.result(),
                source.timestamp(),
                source.nonce(),
                signature,
                source.rawPayload()
        );
    }

    private PaymentCallbackRequest withPaymentNo(PaymentCallbackRequest source, String paymentNo) {
        return new PaymentCallbackRequest(
                source.callbackNo(), paymentNo, source.orderNo(), source.amount(), source.result(),
                source.timestamp(), source.nonce(), source.signature(), source.rawPayload());
    }

    private PaymentCallbackRequest withOrderNo(PaymentCallbackRequest source, String orderNo) {
        return new PaymentCallbackRequest(
                source.callbackNo(), source.paymentNo(), orderNo, source.amount(), source.result(),
                source.timestamp(), source.nonce(), source.signature(), source.rawPayload());
    }

    private PaymentCallbackRequest withAmount(PaymentCallbackRequest source, BigDecimal amount) {
        return new PaymentCallbackRequest(
                source.callbackNo(), source.paymentNo(), source.orderNo(), amount, source.result(),
                source.timestamp(), source.nonce(), source.signature(), source.rawPayload());
    }

    private PaymentCallbackRequest withResult(PaymentCallbackRequest source, String result) {
        return new PaymentCallbackRequest(
                source.callbackNo(), source.paymentNo(), source.orderNo(), source.amount(), result,
                source.timestamp(), source.nonce(), source.signature(), source.rawPayload());
    }

    private PaymentCallbackRequest withTimestamp(PaymentCallbackRequest source, long timestamp) {
        return new PaymentCallbackRequest(
                source.callbackNo(), source.paymentNo(), source.orderNo(), source.amount(), source.result(),
                timestamp, source.nonce(), source.signature(), source.rawPayload());
    }

    private PaymentCallbackRequest withNonce(PaymentCallbackRequest source, String nonce) {
        return new PaymentCallbackRequest(
                source.callbackNo(), source.paymentNo(), source.orderNo(), source.amount(), source.result(),
                source.timestamp(), nonce, source.signature(), source.rawPayload());
    }

    private PaymentCallbackRequest withRawPayload(PaymentCallbackRequest source, String rawPayload) {
        return new PaymentCallbackRequest(
                source.callbackNo(), source.paymentNo(), source.orderNo(), source.amount(), source.result(),
                source.timestamp(), source.nonce(), source.signature(), rawPayload);
    }

    private PaymentOrder payment(Integer secretVersion) {
        PaymentOrder payment = new PaymentOrder();
        payment.setPaymentNo("PAY1");
        payment.setOrderNo("TR1");
        payment.setAmount(new BigDecimal("100.00"));
        payment.setCallbackSecretVersion(secretVersion);
        return payment;
    }

    private PaymentCallbackProperties properties(
            String secret,
            int secretVersion,
            String previousSecret,
            Integer previousSecretVersion
    ) {
        return new PaymentCallbackProperties(
                secret, secretVersion, previousSecret, previousSecretVersion, 300, 900, false);
    }
}
