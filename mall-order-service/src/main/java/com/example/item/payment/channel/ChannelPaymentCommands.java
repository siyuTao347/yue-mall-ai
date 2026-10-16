package com.example.item.payment.channel;

import com.example.item.payment.domain.PaymentMethod;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public class ChannelPaymentCommands {

    public record CashierAction(
            String type,
            String displayToken,
            String payUrl,
            String instructions
    ) {}

    public record CreateChannelPayment(
            String attemptNo,
            String paymentNo,
            String orderNo,
            BigDecimal amount,
            String currency,
            ChannelProfileSnapshot profile,
            String requestKey,
            String clientIp
    ) {}

    public record CreatePaymentResult(
            boolean success,
            String channelTradeNo,
            String status,
            CashierAction cashierAction,
            Instant channelSuccessAt,
            LocalDate channelBusinessDate,
            String failureCode,
            String failureMessage
    ) {
        public static CreatePaymentResult pending(String channelTradeNo, CashierAction action) {
            return new CreatePaymentResult(true, channelTradeNo, "PENDING", action, null, null, null, null);
        }

        public static CreatePaymentResult failed(String code, String message) {
            return new CreatePaymentResult(false, null, "FAILED", null, null, null, code, message);
        }
    }

    public record QueryChannelPayment(
            String attemptNo,
            String outTradeNo,
            String channelTradeNo,
            ChannelProfileSnapshot profile
    ) {}

    public record ChannelPaymentResult(
            boolean exists,
            String channelTradeNo,
            String status,
            BigDecimal amount,
            BigDecimal feeAmount,
            String currency,
            Instant channelSuccessAt,
            LocalDate channelBusinessDate,
            String failureCode
    ) {}

    public record CloseChannelPayment(
            String attemptNo,
            String outTradeNo,
            String channelTradeNo,
            ChannelProfileSnapshot profile
    ) {}

    public record ClosePaymentResult(
            boolean closed,
            String channelStatus,
            String message
    ) {}

    public record RefundChannelPayment(
            String refundNo,
            String originalAttemptNo,
            String channelTradeNo,
            BigDecimal amount,
            String currency,
            String reason,
            ChannelProfileSnapshot profile
    ) {}

    public record ChannelRefundResult(
            boolean success,
            String refundNo,
            String channelRefundNo,
            String status,
            BigDecimal amount,
            BigDecimal feeReturnAmount,
            Instant acceptedAt,
            Instant successAt,
            LocalDate channelBusinessDate,
            String failureCode
    ) {}

    public record QueryChannelRefund(
            String refundNo,
            String channelRefundNo,
            ChannelProfileSnapshot profile
    ) {}

    public record RawChannelNotification(
            String version,
            String eventId,
            PaymentMethod paymentMethod,
            String channelMerchantNo,
            String payloadJson,
            String signature,
            long timestamp,
            String nonce
    ) {}

    public record VerifiedChannelEvent(
            String eventId,
            String profileId,
            String channelMerchantNo,
            String attemptNo,
            String refundNo,
            String channelTradeNo,
            String eventType,
            BigDecimal amount,
            BigDecimal feeAmount,
            String currency,
            Instant occurredAt,
            LocalDate channelBusinessDate,
            String status,
            String rawPayloadHash,
            String verifiedJson
    ) {}
}
