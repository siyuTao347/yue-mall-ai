package com.example.item.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.item.config.PaymentCallbackProperties;
import com.example.item.dto.PaymentCallbackRequest;
import com.example.item.entity.PaymentCallback;
import com.example.item.entity.PaymentCallbackNonce;
import com.example.item.entity.PaymentOrder;
import com.example.item.mapper.PaymentCallbackMapper;
import com.example.item.mapper.PaymentCallbackNonceMapper;
import com.example.item.mapper.PaymentOrderMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

@Service
@Slf4j
public class PaymentCallbackService {

    private static final String VERIFY_PASSED = "VERIFY_PASSED";
    private static final String VERIFY_FAILED = "VERIFY_FAILED";
    private static final String HMAC_SHA256 = "HMAC-SHA256";

    private final PaymentOrderMapper paymentMapper;
    private final PaymentCallbackMapper callbackMapper;
    private final PaymentCallbackNonceMapper nonceMapper;
    private final PaymentCallbackVerifier verifier;
    private final TradeOrderService tradeOrderService;
    private final PaymentCallbackProperties properties;
    private final TransactionTemplate transactionTemplate;

    public PaymentCallbackService(
            PaymentOrderMapper paymentMapper,
            PaymentCallbackMapper callbackMapper,
            PaymentCallbackNonceMapper nonceMapper,
            PaymentCallbackVerifier verifier,
            TradeOrderService tradeOrderService,
            PaymentCallbackProperties properties,
            TransactionTemplate transactionTemplate
    ) {
        this.paymentMapper = paymentMapper;
        this.callbackMapper = callbackMapper;
        this.nonceMapper = nonceMapper;
        this.verifier = verifier;
        this.tradeOrderService = tradeOrderService;
        this.properties = properties;
        this.transactionTemplate = transactionTemplate;
    }

    public String handle(PaymentCallbackRequest request) {
        PaymentOrder payment = findPayment(request);
        if (payment == null) {
            reject(request, null, "PAY_CALLBACK_NOT_FOUND", HttpStatus.NOT_FOUND);
        }
        if (!payment.getOrderNo().equals(request.orderNo())) {
            reject(request, payment, "PAY_CALLBACK_ORDER_MISMATCH", HttpStatus.BAD_REQUEST);
        }
        if (isTimestampExpired(request.timestamp())) {
            reject(request, payment, "PAY_CALLBACK_TIMESTAMP_EXPIRED", HttpStatus.UNAUTHORIZED);
        }
        if (nonceMapper.countByNonce(request.nonce()) > 0) {
            reject(request, payment, "PAY_CALLBACK_NONCE_DUPLICATED", HttpStatus.CONFLICT);
        }
        if (!verifier.verify(request, payment)) {
            rejectAfterNonceReserved(request, payment, "PAY_CALLBACK_SIGNATURE_INVALID", HttpStatus.UNAUTHORIZED);
        }
        if (request.amount().compareTo(payment.getAmount()) != 0) {
            rejectAfterNonceReserved(request, payment, "PAY_CALLBACK_AMOUNT_MISMATCH", HttpStatus.BAD_REQUEST);
        }

        return transactionTemplate.execute(status -> {
            if (!reserveNonce(request, payment)) {
                throw new PaymentCallbackRejectedException(
                        "PAY_CALLBACK_NONCE_DUPLICATED",
                        HttpStatus.CONFLICT
                );
            }
            if (!saveCallback(request, payment, VERIFY_PASSED, null)) {
                return "PAY_CALLBACK_DUPLICATED";
            }
            return tradeOrderService.handleVerifiedCallback(request, payment);
        });
    }

    private PaymentOrder findPayment(PaymentCallbackRequest request) {
        return paymentMapper.selectOne(new LambdaQueryWrapper<PaymentOrder>()
                .eq(PaymentOrder::getPaymentNo, request.paymentNo()));
    }

    private boolean isTimestampExpired(long timestamp) {
        return Math.abs(System.currentTimeMillis() - timestamp) > verifier.clockSkewMillis();
    }

    private boolean reserveNonce(PaymentCallbackRequest request, PaymentOrder payment) {
        PaymentCallbackNonce nonce = new PaymentCallbackNonce();
        nonce.setNonce(request.nonce());
        nonce.setPaymentNo(request.paymentNo());
        nonce.setSecretVersion(verifier.resolveSecretVersion(payment));
        nonce.setExpireTime(LocalDateTime.now().plusSeconds(properties.nonceTtlSeconds()));
        nonce.setCreatedTime(LocalDateTime.now());
        try {
            return nonceMapper.insert(nonce) > 0;
        } catch (DuplicateKeyException ignored) {
            return false;
        }
    }

    private void reject(
            PaymentCallbackRequest request,
            PaymentOrder payment,
            String code,
            HttpStatus status
    ) {
        transactionTemplate.executeWithoutResult(status1 ->
                saveCallback(request, payment, VERIFY_FAILED, code));
        logRejected(code, request);
        throw new PaymentCallbackRejectedException(code, status);
    }

    private void rejectAfterNonceReserved(
            PaymentCallbackRequest request,
            PaymentOrder payment,
            String code,
            HttpStatus status
    ) {
        transactionTemplate.executeWithoutResult(status1 -> {
            if (reserveNonce(request, payment)) {
                saveCallback(request, payment, VERIFY_FAILED, code);
            }
        });
        logRejected(code, request);
        throw new PaymentCallbackRejectedException(code, status);
    }

    private boolean saveCallback(
            PaymentCallbackRequest request,
            PaymentOrder payment,
            String verifyStatus,
            String failureReason
    ) {
        PaymentCallback callback = new PaymentCallback();
        callback.setCallbackNo(request.callbackNo());
        callback.setPaymentNo(request.paymentNo());
        callback.setResult(request.result());
        callback.setAmount(request.amount());
        callback.setSignature(request.signature());
        callback.setRawPayload(request.rawPayload());
        callback.setTimestampEpochMs(request.timestamp());
        callback.setNonce(request.nonce());
        callback.setSignatureAlgorithm(HMAC_SHA256);
        callback.setSecretVersion(payment == null ? properties.secretVersion() : verifier.resolveSecretVersion(payment));
        callback.setVerifyStatus(verifyStatus);
        callback.setFailureReason(failureReason);
        callback.setReceivedTime(LocalDateTime.now());
        try {
            return callbackMapper.insert(callback) > 0;
        } catch (org.springframework.dao.DuplicateKeyException ignored) {
            return false;
        }
    }

    private void logRejected(String code, PaymentCallbackRequest request) {
        log.warn("payment callback rejected: code={}, paymentNo={}, callbackNo={}",
                code, request.paymentNo(), request.callbackNo());
    }
}
