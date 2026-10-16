package com.example.item.payment.mock;

import com.example.item.payment.channel.ChannelPaymentCommands.*;
import com.example.item.payment.channel.ChannelProfileSnapshot;
import com.example.item.payment.domain.PaymentMethod;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

@Service
@Slf4j
public class MockChannelEngine {

    private static final String DEFAULT_HMAC_SECRET = "mall_mock_channel_hmac_secret_2026_key";

    private final MockChannelTransactionMapper transactionMapper;
    private final MockNotificationOutboxMapper outboxMapper;
    private final MockChannelRefundMapper refundMapper;
    private final MockChannelPostingMapper postingMapper;
    private final MockFaultPlanMapper faultPlanMapper;
    private final ObjectMapper objectMapper;

    public MockChannelEngine(
            MockChannelTransactionMapper transactionMapper,
            MockNotificationOutboxMapper outboxMapper,
            MockChannelRefundMapper refundMapper,
            MockChannelPostingMapper postingMapper,
            MockFaultPlanMapper faultPlanMapper,
            ObjectMapper objectMapper
    ) {
        this.transactionMapper = transactionMapper;
        this.outboxMapper = outboxMapper;
        this.refundMapper = refundMapper;
        this.postingMapper = postingMapper;
        this.faultPlanMapper = faultPlanMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * 独立事务：模拟渠道接收创建支付请求
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public CreatePaymentResult processCreatePayment(CreateChannelPayment command, CashierAction action) {
        ChannelProfileSnapshot profile = command.profile();
        MockChannelTransaction existing = transactionMapper.selectByOutTradeNo(command.attemptNo());
        if (existing != null) {
            return CreatePaymentResult.pending(existing.getChannelTradeNo(), action);
        }

        String channelTradeNo = "MOCK_TX_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase();
        return CreatePaymentResult.pending(channelTradeNo, action);
    }

    /**
     * 独立事务：用户在 Mock 收银台确认支付成功
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public ChannelPaymentResult processConfirmPayment(
            String attemptNo,
            PaymentMethod method,
            ChannelProfileSnapshot profile,
            BigDecimal amount,
            String clientResult
    ) {
        MockChannelTransaction existing = transactionMapper.selectByOutTradeNo(attemptNo);
        if (existing != null) {
            return new ChannelPaymentResult(
                    true,
                    existing.getChannelTradeNo(),
                    existing.getStatus(),
                    existing.getAmount(),
                    existing.getFeeAmount(),
                    existing.getCurrency(),
                    existing.getChannelSuccessAt().atZone(profile.getZone()).toInstant(),
                    existing.getChannelBusinessDate(),
                    null
            );
        }

        LocalDateTime now = LocalDateTime.now(profile.getZone());
        LocalDate businessDate = calculateBusinessDate(now, profile.getCutoff());
        String status = "FAIL".equalsIgnoreCase(clientResult) ? "FAIL" : "SUCCESS";
        String channelTradeNo = "MOCK_TX_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase();
        BigDecimal fee = profile.calculateFee(amount);

        // 检查故障注入计划
        MockFaultPlan dropPlan = faultPlanMapper.findActivePlan("DEFAULT", "DROP_PAYMENT_NOTIFY", attemptNo, businessDate);
        MockFaultPlan amountPlan = faultPlanMapper.findActivePlan("DEFAULT", "AMOUNT_MISMATCH", attemptNo, businessDate);
        BigDecimal actualChannelAmount = amount;
        if (amountPlan != null) {
            actualChannelAmount = amount.subtract(BigDecimal.ONE);
        }

        MockChannelTransaction tx = MockChannelTransaction.builder()
                .profileId(profile.profileId())
                .channelMerchantNo(profile.channelMerchantNo())
                .outTradeNo(attemptNo)
                .channelTradeNo(channelTradeNo)
                .paymentMethod(method.name())
                .amount(actualChannelAmount)
                .feeAmount(fee)
                .currency("CNY")
                .status(status)
                .channelSuccessAt(now)
                .channelBusinessDate(businessDate)
                .faultPlanId(amountPlan != null ? "AMOUNT_MISMATCH" : (dropPlan != null ? "DROP_PAYMENT_NOTIFY" : null))
                .rawDetails("{\"mode\":\"MOCK\",\"result\":\"" + status + "\"}")
                .createdTime(now)
                .updatedTime(now)
                .build();
        transactionMapper.insert(tx);

        if ("SUCCESS".equals(status)) {
            // 记渠道动账流水
            long seq = postingMapper.getMaxSequence(profile.accountId()) + 1;
            BigDecimal prevBalance = postingMapper.getLatestBalance(profile.accountId());
            if (prevBalance == null) {
                prevBalance = BigDecimal.ZERO;
            }
            BigDecimal newBalance = prevBalance.add(actualChannelAmount);

            MockChannelPosting posting = MockChannelPosting.builder()
                    .accountId(profile.accountId())
                    .sequenceNo(seq)
                    .entryType("PAYMENT")
                    .sourceEventNo(channelTradeNo)
                    .amount(actualChannelAmount)
                    .direction("CREDIT")
                    .balanceAfter(newBalance)
                    .channelBusinessDate(businessDate)
                    .occurredAt(now)
                    .createdTime(now)
                    .build();
            postingMapper.insert(posting);

            // 如果没有注入丢通知故障，落 Outbox
            if (dropPlan == null) {
                createOutboxNotification(profile, attemptNo, null, channelTradeNo, "PAYMENT",
                        actualChannelAmount, fee, "CNY", status, now, businessDate);
            } else {
                log.info("Mock fault injected: DROP_PAYMENT_NOTIFY for attemptNo={}", attemptNo);
            }
        }

        return new ChannelPaymentResult(
                true,
                channelTradeNo,
                status,
                actualChannelAmount,
                fee,
                "CNY",
                now.atZone(profile.getZone()).toInstant(),
                businessDate,
                null
        );
    }

    /**
     * 独立事务：查询渠道交易
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public ChannelPaymentResult queryPayment(QueryChannelPayment query) {
        MockChannelTransaction tx = transactionMapper.selectByOutTradeNo(query.attemptNo());
        if (tx == null && query.channelTradeNo() != null && !query.channelTradeNo().isBlank()) {
            tx = transactionMapper.selectByChannelTradeNo(query.channelTradeNo());
        }
        if (tx == null) {
            return new ChannelPaymentResult(false, null, "NOT_FOUND", null, null, null, null, null, "CHANNEL_TRANSACTION_NOT_FOUND");
        }
        return new ChannelPaymentResult(
                true,
                tx.getChannelTradeNo(),
                tx.getStatus(),
                tx.getAmount(),
                tx.getFeeAmount(),
                tx.getCurrency(),
                tx.getChannelSuccessAt().atZone(query.profile().getZone()).toInstant(),
                tx.getChannelBusinessDate(),
                null
        );
    }

    /**
     * 独立事务：关单
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public ClosePaymentResult closePayment(CloseChannelPayment command) {
        MockChannelTransaction tx = transactionMapper.selectByOutTradeNo(command.attemptNo());
        if (tx == null) {
            return new ClosePaymentResult(true, "CLOSED", "渠道未支付，已关闭");
        }
        if ("SUCCESS".equals(tx.getStatus())) {
            return new ClosePaymentResult(false, "SUCCESS", "渠道已支付成功，无法关闭");
        }
        tx.setStatus("CLOSED");
        tx.setUpdatedTime(LocalDateTime.now());
        transactionMapper.updateById(tx);
        return new ClosePaymentResult(true, "CLOSED", "渠道订单已关闭");
    }

    /**
     * 独立事务：原路退款
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public ChannelRefundResult processRefund(RefundChannelPayment command) {
        ChannelProfileSnapshot profile = command.profile();
        MockChannelRefund existing = refundMapper.selectByRefundNo(command.refundNo());
        if (existing != null) {
            return new ChannelRefundResult(
                    true,
                    existing.getRefundNo(),
                    existing.getChannelRefundNo(),
                    existing.getStatus(),
                    existing.getAmount(),
                    existing.getFeeReturnAmount(),
                    existing.getAcceptedAt().atZone(profile.getZone()).toInstant(),
                    existing.getSuccessAt() != null ? existing.getSuccessAt().atZone(profile.getZone()).toInstant() : null,
                    existing.getChannelBusinessDate(),
                    null
            );
        }

        MockChannelTransaction origTx = transactionMapper.selectByOutTradeNo(command.originalAttemptNo());
        if (origTx == null && command.channelTradeNo() != null) {
            origTx = transactionMapper.selectByChannelTradeNo(command.channelTradeNo());
        }
        if (origTx == null || !"SUCCESS".equals(origTx.getStatus())) {
            return new ChannelRefundResult(false, command.refundNo(), null, "FAILED", command.amount(),
                    BigDecimal.ZERO, Instant.now(), null, LocalDate.now(), "ORIGINAL_PAYMENT_NOT_FOUND");
        }

        LocalDateTime now = LocalDateTime.now(profile.getZone());
        LocalDate businessDate = calculateBusinessDate(now, profile.getCutoff());
        String channelRefundNo = "MOCK_REF_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase();
        BigDecimal feeReturn = profile.calculateFee(command.amount());

        MockChannelRefund refund = MockChannelRefund.builder()
                .channelMerchantNo(profile.channelMerchantNo())
                .refundNo(command.refundNo())
                .channelRefundNo(channelRefundNo)
                .channelTradeNo(origTx.getChannelTradeNo())
                .amount(command.amount())
                .feeReturnAmount(feeReturn)
                .currency("CNY")
                .status("SUCCEEDED")
                .acceptedAt(now)
                .successAt(now)
                .channelBusinessDate(businessDate)
                .createdTime(now)
                .updatedTime(now)
                .build();
        refundMapper.insert(refund);

        // 记账户扣减流水
        long seq = postingMapper.getMaxSequence(profile.accountId()) + 1;
        BigDecimal prevBalance = postingMapper.getLatestBalance(profile.accountId());
        if (prevBalance == null) {
            prevBalance = BigDecimal.ZERO;
        }
        BigDecimal newBalance = prevBalance.subtract(command.amount());

        MockChannelPosting posting = MockChannelPosting.builder()
                .accountId(profile.accountId())
                .sequenceNo(seq)
                .entryType("REFUND")
                .sourceEventNo(channelRefundNo)
                .amount(command.amount())
                .direction("DEBIT")
                .balanceAfter(newBalance)
                .channelBusinessDate(businessDate)
                .occurredAt(now)
                .createdTime(now)
                .build();
        postingMapper.insert(posting);

        // 落 Outbox
        createOutboxNotification(profile, command.originalAttemptNo(), command.refundNo(), channelRefundNo,
                "REFUND", command.amount(), feeReturn, "CNY", "SUCCEEDED", now, businessDate);

        return new ChannelRefundResult(
                true,
                command.refundNo(),
                channelRefundNo,
                "SUCCEEDED",
                command.amount(),
                feeReturn,
                now.atZone(profile.getZone()).toInstant(),
                now.atZone(profile.getZone()).toInstant(),
                businessDate,
                null
        );
    }

    /**
     * 独立事务：查询退款
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public ChannelRefundResult queryRefund(QueryChannelRefund query) {
        MockChannelRefund refund = refundMapper.selectByRefundNo(query.refundNo());
        if (refund == null) {
            return new ChannelRefundResult(false, query.refundNo(), null, "NOT_FOUND",
                    null, null, null, null, null, "REFUND_NOT_FOUND");
        }
        return new ChannelRefundResult(
                true,
                refund.getRefundNo(),
                refund.getChannelRefundNo(),
                refund.getStatus(),
                refund.getAmount(),
                refund.getFeeReturnAmount(),
                refund.getAcceptedAt().atZone(query.profile().getZone()).toInstant(),
                refund.getSuccessAt() != null ? refund.getSuccessAt().atZone(query.profile().getZone()).toInstant() : null,
                refund.getChannelBusinessDate(),
                null
        );
    }

    public boolean verifyNotificationSignature(String payloadJson, String signature) {
        String expectedSign = sign(payloadJson);
        return expectedSign.equalsIgnoreCase(signature);
    }

    public boolean verifyNotificationSignature(String profileId, String payloadJson, String signature) {
        return verifyNotificationSignature(payloadJson, signature);
    }

    public VerifiedChannelEvent parseVerifiedEvent(String payloadJson, String signature) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = objectMapper.readValue(payloadJson, Map.class);
            String profileId = String.valueOf(map.get("profileId"));
            PaymentMethod method;
            if (profileId != null && profileId.startsWith("MOCK_")) {
                method = PaymentMethod.valueOf(profileId.substring(5));
            } else {
                method = PaymentMethod.UNIONPAY_BANK;
            }
            ChannelProfileSnapshot profile = ChannelProfileSnapshot.defaultFor(method);
            RawChannelNotification notification = new RawChannelNotification(
                    "2.0",
                    String.valueOf(map.get("eventId")),
                    profile.paymentMethod(),
                    profile.channelMerchantNo(),
                    payloadJson,
                    signature,
                    System.currentTimeMillis(),
                    ""
            );
            return verifyAndParse(notification, profile);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse verified event: " + e.getMessage(), e);
        }
    }

    public VerifiedChannelEvent verifyAndParse(RawChannelNotification notification, ChannelProfileSnapshot profile) {
        String expectedSign = sign(notification.payloadJson());
        if (!expectedSign.equalsIgnoreCase(notification.signature())) {
            throw new IllegalArgumentException("SIGNATURE_INVALID");
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = objectMapper.readValue(notification.payloadJson(), Map.class);
            String eventId = String.valueOf(map.get("eventId"));
            String attemptNo = (String) map.get("attemptNo");
            String refundNo = (String) map.get("refundNo");
            String channelTradeNo = (String) map.get("channelTradeNo");
            String eventType = String.valueOf(map.get("eventType"));
            BigDecimal amount = new BigDecimal(String.valueOf(map.get("amount")));
            BigDecimal fee = map.get("feeAmount") != null ? new BigDecimal(String.valueOf(map.get("feeAmount"))) : BigDecimal.ZERO;
            String currency = (String) map.getOrDefault("currency", "CNY");
            String status = String.valueOf(map.get("status"));
            long epochMs = ((Number) map.getOrDefault("timestamp", System.currentTimeMillis())).longValue();
            Instant occurredAt = Instant.ofEpochMilli(epochMs);
            LocalDate bizDate = map.get("businessDate") != null ? LocalDate.parse(String.valueOf(map.get("businessDate"))) : LocalDate.now();

            String rawPayloadHash = sha256(notification.payloadJson());

            return new VerifiedChannelEvent(
                    eventId,
                    profile.profileId(),
                    profile.channelMerchantNo(),
                    attemptNo,
                    refundNo,
                    channelTradeNo,
                    eventType,
                    amount,
                    fee,
                    currency,
                    occurredAt,
                    bizDate,
                    status,
                    rawPayloadHash,
                    notification.payloadJson()
            );
        } catch (Exception e) {
            throw new IllegalArgumentException("FAILED_TO_PARSE_NOTIFICATION: " + e.getMessage(), e);
        }
    }

    public String sign(String content) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(DEFAULT_HMAC_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] bytes = mac.doFinal(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to calculate HMAC-SHA256 signature", e);
        }
    }

    public static String sha256(String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static LocalDate calculateBusinessDate(LocalDateTime time, LocalTime cutoff) {
        if (cutoff == null || cutoff.equals(LocalTime.MIDNIGHT)) {
            return time.toLocalDate();
        }
        // 若日切为 23:00，则 23:00 之后发生的交易属于下一业务日
        if (!time.toLocalTime().isBefore(cutoff)) {
            return time.toLocalDate().plusDays(1);
        }
        return time.toLocalDate();
    }

    private void createOutboxNotification(
            ChannelProfileSnapshot profile,
            String attemptNo,
            String refundNo,
            String channelTradeNo,
            String eventType,
            BigDecimal amount,
            BigDecimal fee,
            String currency,
            String status,
            LocalDateTime now,
            LocalDate businessDate
    ) {
        String eventId = "EVT_" + UUID.randomUUID().toString().replace("-", "");
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("version", "2.0");
        payload.put("eventId", eventId);
        payload.put("profileId", profile.profileId());
        payload.put("channelMerchantNo", profile.channelMerchantNo());
        payload.put("attemptNo", attemptNo != null ? attemptNo : "");
        payload.put("refundNo", refundNo != null ? refundNo : "");
        payload.put("channelTradeNo", channelTradeNo);
        payload.put("eventType", eventType);
        payload.put("amount", amount.toPlainString());
        payload.put("feeAmount", fee.toPlainString());
        payload.put("currency", currency);
        payload.put("status", status);
        payload.put("businessDate", businessDate.toString());
        payload.put("timestamp", now.atZone(profile.getZone()).toInstant().toEpochMilli());

        try {
            String json = objectMapper.writeValueAsString(payload);
            String sig = sign(json);

            MockNotificationOutbox outbox = MockNotificationOutbox.builder()
                    .eventId(eventId)
                    .profileId(profile.profileId())
                    .channelMerchantNo(profile.channelMerchantNo())
                    .eventType(eventType)
                    .payloadJson(json)
                    .signature(sig)
                    .retryCount(0)
                    .nextSendAt(now)
                    .status("PENDING")
                    .createdTime(now)
                    .updatedTime(now)
                    .build();
            outboxMapper.insert(outbox);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize mock outbox notification", e);
        }
    }
}
