package com.example.item.service;

import com.example.item.config.PaymentCallbackProperties;
import com.example.item.dto.PaymentCallbackRequest;
import com.example.item.entity.PaymentCallback;
import com.example.item.entity.PaymentCallbackNonce;
import com.example.item.entity.PaymentOrder;
import com.example.item.mapper.PaymentCallbackMapper;
import com.example.item.mapper.PaymentCallbackNonceMapper;
import com.example.item.mapper.PaymentOrderMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;

class PaymentCallbackServiceTest {

    private PaymentOrderMapper paymentMapper;
    private PaymentCallbackMapper callbackMapper;
    private PaymentCallbackNonceMapper nonceMapper;
    private TradeOrderService tradeOrderService;
    private TransactionTemplate transactionTemplate;
    private PaymentCallbackService service;
    private PaymentCallbackVerifier verifier;

    @BeforeEach
    void setUp() {
        paymentMapper = mock(PaymentOrderMapper.class);
        callbackMapper = mock(PaymentCallbackMapper.class);
        nonceMapper = mock(PaymentCallbackNonceMapper.class);
        tradeOrderService = mock(TradeOrderService.class);
        transactionTemplate = mock(TransactionTemplate.class);
        doAnswer(invocation -> {
            Consumer<TransactionStatus> action = invocation.getArgument(0);
            action.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        PaymentCallbackProperties properties = new PaymentCallbackProperties(
                "test-secret", 1, null, null, 300, 900, false);
        verifier = new PaymentCallbackVerifier(properties);
        service = new PaymentCallbackService(
                paymentMapper, callbackMapper, nonceMapper, verifier, tradeOrderService,
                properties, transactionTemplate);
    }

    @Test
    void acceptsValidCallbackAndReservesNonce() {
        PaymentOrder payment = payment();
        PaymentCallbackRequest request = signedRequest(payment);
        when(paymentMapper.selectOne(any())).thenReturn(payment);
        when(nonceMapper.countByNonce(request.nonce())).thenReturn(0L);
        when(nonceMapper.insert(any(PaymentCallbackNonce.class))).thenReturn(1);
        when(callbackMapper.insert(any(PaymentCallback.class))).thenReturn(1);
        when(tradeOrderService.handleVerifiedCallback(request, payment)).thenReturn("PAY_CALLBACK_ACCEPTED");

        String result = service.handle(request);

        Assertions.assertEquals("PAY_CALLBACK_ACCEPTED", result);
        ArgumentCaptor<PaymentCallbackNonce> nonceCaptor =
                ArgumentCaptor.forClass(PaymentCallbackNonce.class);
        verify(nonceMapper).insert(nonceCaptor.capture());
        Assertions.assertEquals(request.nonce(), nonceCaptor.getValue().getNonce());
        Assertions.assertEquals(payment.getPaymentNo(), nonceCaptor.getValue().getPaymentNo());
        Assertions.assertEquals(1, nonceCaptor.getValue().getSecretVersion());
        Assertions.assertNotNull(nonceCaptor.getValue().getExpireTime());
    }

    @Test
    void rejectsMissingPayment() {
        PaymentCallbackRequest request = signedRequest(payment());
        when(paymentMapper.selectOne(any())).thenReturn(null);
        when(callbackMapper.insert(any(PaymentCallback.class))).thenReturn(1);

        PaymentCallbackRejectedException exception = Assertions.assertThrows(
                PaymentCallbackRejectedException.class, () -> service.handle(request));

        Assertions.assertEquals("PAY_CALLBACK_NOT_FOUND", exception.code());
        Assertions.assertEquals(HttpStatus.NOT_FOUND, exception.status());
        verify(nonceMapper, never()).insert(any(PaymentCallbackNonce.class));
        verify(tradeOrderService, never()).handleVerifiedCallback(any(), any());
    }

    @Test
    void rejectsPaymentOrderMismatch() {
        PaymentOrder payment = payment();
        PaymentCallbackRequest request = withOrderNo(signedRequest(payment), "TR2");
        when(paymentMapper.selectOne(any())).thenReturn(payment);
        when(callbackMapper.insert(any(PaymentCallback.class))).thenReturn(1);

        PaymentCallbackRejectedException exception = Assertions.assertThrows(
                PaymentCallbackRejectedException.class, () -> service.handle(request));

        Assertions.assertEquals("PAY_CALLBACK_ORDER_MISMATCH", exception.code());
        Assertions.assertEquals(HttpStatus.BAD_REQUEST, exception.status());
        verify(tradeOrderService, never()).handleVerifiedCallback(any(), any());
    }

    @Test
    void rejectsExpiredTimestamp() {
        PaymentOrder payment = payment();
        PaymentCallbackRequest request = signedRequest(payment);
        PaymentCallbackRequest expiredRequest = withTimestamp(
                request, System.currentTimeMillis() - 301_000L);
        when(paymentMapper.selectOne(any())).thenReturn(payment);
        when(callbackMapper.insert(any(PaymentCallback.class))).thenReturn(1);

        PaymentCallbackRejectedException exception = Assertions.assertThrows(
                PaymentCallbackRejectedException.class, () -> service.handle(expiredRequest));

        Assertions.assertEquals("PAY_CALLBACK_TIMESTAMP_EXPIRED", exception.code());
        Assertions.assertEquals(HttpStatus.UNAUTHORIZED, exception.status());
        verify(tradeOrderService, never()).handleVerifiedCallback(any(), any());
    }

    @Test
    void rejectsReplayedNonceWithDifferentCallbackNo() {
        PaymentOrder payment = payment();
        PaymentCallbackRequest request = signedRequest(payment);
        PaymentCallbackRequest replayedRequest = withCallbackNo(request, "CB2");
        when(paymentMapper.selectOne(any())).thenReturn(payment);
        when(nonceMapper.countByNonce(request.nonce())).thenReturn(1L);
        when(callbackMapper.insert(any(PaymentCallback.class))).thenReturn(1);

        PaymentCallbackRejectedException exception = Assertions.assertThrows(
                PaymentCallbackRejectedException.class, () -> service.handle(replayedRequest));

        Assertions.assertEquals("PAY_CALLBACK_NONCE_DUPLICATED", exception.code());
        Assertions.assertEquals(HttpStatus.CONFLICT, exception.status());
        verify(tradeOrderService, never()).handleVerifiedCallback(any(), any());
    }

    @Test
    void rejectsInvalidSignatureAndRecordsFailedCallback() {
        PaymentOrder payment = payment();
        PaymentCallbackRequest request = withSignature(signedRequest(payment), "0".repeat(64));
        when(paymentMapper.selectOne(any())).thenReturn(payment);
        when(nonceMapper.countByNonce(request.nonce())).thenReturn(0L);
        when(nonceMapper.insert(any(PaymentCallbackNonce.class))).thenReturn(1);
        when(callbackMapper.insert(any(PaymentCallback.class))).thenReturn(1);

        PaymentCallbackRejectedException exception = Assertions.assertThrows(
                PaymentCallbackRejectedException.class, () -> service.handle(request));

        Assertions.assertEquals("PAY_CALLBACK_SIGNATURE_INVALID", exception.code());
        Assertions.assertEquals(HttpStatus.UNAUTHORIZED, exception.status());
        ArgumentCaptor<PaymentCallback> callbackCaptor = ArgumentCaptor.forClass(PaymentCallback.class);
        verify(callbackMapper).insert(callbackCaptor.capture());
        Assertions.assertEquals("VERIFY_FAILED", callbackCaptor.getValue().getVerifyStatus());
        Assertions.assertEquals("PAY_CALLBACK_SIGNATURE_INVALID", callbackCaptor.getValue().getFailureReason());
        verify(tradeOrderService, never()).handleVerifiedCallback(any(), any());
    }

    @Test
    void rejectsAmountMismatchWithPaymentOrder() {
        PaymentOrder payment = payment();
        PaymentCallbackRequest request = signedRequest(payment, new BigDecimal("99.00"));
        when(paymentMapper.selectOne(any())).thenReturn(payment);
        when(nonceMapper.countByNonce(request.nonce())).thenReturn(0L);
        when(nonceMapper.insert(any(PaymentCallbackNonce.class))).thenReturn(1);
        when(callbackMapper.insert(any(PaymentCallback.class))).thenReturn(1);

        PaymentCallbackRejectedException exception = Assertions.assertThrows(
                PaymentCallbackRejectedException.class, () -> service.handle(request));

        Assertions.assertEquals("PAY_CALLBACK_AMOUNT_MISMATCH", exception.code());
        Assertions.assertEquals(HttpStatus.BAD_REQUEST, exception.status());
        verify(tradeOrderService, never()).handleVerifiedCallback(any(), any());
    }

    @Test
    void returnsDuplicatedWhenCallbackRecordAlreadyExists() {
        PaymentOrder payment = payment();
        PaymentCallbackRequest request = signedRequest(payment);
        when(paymentMapper.selectOne(any())).thenReturn(payment);
        when(nonceMapper.countByNonce(request.nonce())).thenReturn(0L);
        when(nonceMapper.insert(any(PaymentCallbackNonce.class))).thenReturn(1);
        when(callbackMapper.insert(any(PaymentCallback.class)))
                .thenThrow(new DuplicateKeyException("callback duplicated"));

        String result = service.handle(request);

        Assertions.assertEquals("PAY_CALLBACK_DUPLICATED", result);
        verify(tradeOrderService, never()).handleVerifiedCallback(any(), any());
    }

    @Test
    void rejectsConcurrentNonceInsert() {
        PaymentOrder payment = payment();
        PaymentCallbackRequest request = signedRequest(payment);
        when(paymentMapper.selectOne(any())).thenReturn(payment);
        when(nonceMapper.countByNonce(request.nonce())).thenReturn(0L);
        when(nonceMapper.insert(any(PaymentCallbackNonce.class)))
                .thenThrow(new DuplicateKeyException("nonce duplicated"));

        PaymentCallbackRejectedException exception = Assertions.assertThrows(
                PaymentCallbackRejectedException.class, () -> service.handle(request));

        Assertions.assertEquals("PAY_CALLBACK_NONCE_DUPLICATED", exception.code());
        Assertions.assertEquals(HttpStatus.CONFLICT, exception.status());
        verify(callbackMapper, never()).insert(any(PaymentCallback.class));
        verify(tradeOrderService, never()).handleVerifiedCallback(any(), any());
    }

    private PaymentCallbackRequest signedRequest(PaymentOrder payment) {
        return signedRequest(payment, payment.getAmount());
    }

    private PaymentCallbackRequest signedRequest(PaymentOrder payment, BigDecimal amount) {
        PaymentCallbackRequest unsignedRequest = request(amount, "signature-placeholder");
        return withSignature(unsignedRequest, verifier.sign(unsignedRequest, payment));
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
                source.callbackNo(), source.paymentNo(), source.orderNo(), source.amount(),
                source.result(), source.timestamp(), source.nonce(), signature, source.rawPayload());
    }

    private PaymentCallbackRequest withCallbackNo(PaymentCallbackRequest source, String callbackNo) {
        return new PaymentCallbackRequest(
                callbackNo, source.paymentNo(), source.orderNo(), source.amount(),
                source.result(), source.timestamp(), source.nonce(), source.signature(), source.rawPayload());
    }

    private PaymentCallbackRequest withOrderNo(PaymentCallbackRequest source, String orderNo) {
        return new PaymentCallbackRequest(
                source.callbackNo(), source.paymentNo(), orderNo, source.amount(),
                source.result(), source.timestamp(), source.nonce(), source.signature(), source.rawPayload());
    }

    private PaymentCallbackRequest withTimestamp(PaymentCallbackRequest source, long timestamp) {
        return new PaymentCallbackRequest(
                source.callbackNo(), source.paymentNo(), source.orderNo(), source.amount(),
                source.result(), timestamp, source.nonce(), source.signature(), source.rawPayload());
    }

    private PaymentOrder payment() {
        PaymentOrder payment = new PaymentOrder();
        payment.setPaymentNo("PAY1");
        payment.setOrderNo("TR1");
        payment.setAmount(new BigDecimal("100.00"));
        payment.setStatus("PAYING");
        payment.setCallbackSecretVersion(1);
        return payment;
    }
}
