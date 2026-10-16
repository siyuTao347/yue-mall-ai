package com.example.item.payment.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.item.entity.PaymentOrder;
import com.example.item.entity.TradeOrder;
import com.example.item.mapper.PaymentOrderMapper;
import com.example.item.mapper.TradeOrderMapper;
import com.example.item.payment.channel.ChannelPaymentCommands.*;
import com.example.item.payment.channel.ChannelProfileSnapshot;
import com.example.item.payment.channel.PaymentChannelStrategy;
import com.example.item.payment.channel.PaymentStrategyRegistry;
import com.example.item.payment.domain.PaymentAttempt;
import com.example.item.payment.domain.PaymentMethod;
import com.example.item.payment.mapper.PaymentAttemptMapper;
import com.example.item.payment.mock.MockChannelEngine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.item.payment.domain.PaymentRefundOrder;
import com.example.item.payment.domain.PaymentFinancialEvent;
import com.example.item.payment.mapper.PaymentRefundOrderMapper;
import com.example.item.payment.mapper.PaymentFinancialEventMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

@Service
@Slf4j
public class PaymentApplicationService {

    private final PaymentOrderMapper paymentMapper;
    private final PaymentAttemptMapper attemptMapper;
    private final TradeOrderMapper orderMapper;
    private final PaymentRefundOrderMapper refundOrderMapper;
    private final PaymentFinancialEventMapper financialEventMapper;
    private final PaymentStrategyRegistry strategyRegistry;
    private final MockChannelEngine mockChannelEngine;
    private final PaymentConfirmationService confirmationService;

    public PaymentApplicationService(
            PaymentOrderMapper paymentMapper,
            PaymentAttemptMapper attemptMapper,
            TradeOrderMapper orderMapper,
            PaymentRefundOrderMapper refundOrderMapper,
            PaymentFinancialEventMapper financialEventMapper,
            PaymentStrategyRegistry strategyRegistry,
            MockChannelEngine mockChannelEngine,
            PaymentConfirmationService confirmationService
    ) {
        this.paymentMapper = paymentMapper;
        this.attemptMapper = attemptMapper;
        this.orderMapper = orderMapper;
        this.refundOrderMapper = refundOrderMapper;
        this.financialEventMapper = financialEventMapper;
        this.strategyRegistry = strategyRegistry;
        this.mockChannelEngine = mockChannelEngine;
        this.confirmationService = confirmationService;
    }

    public List<Map<String, Object>> getAvailableMethods(String paymentNo, Long userId) {
        PaymentOrder payment = requirePayment(paymentNo);
        TradeOrder order = requireOrder(payment.getOrderNo());
        validateOrderVisible(order, userId);

        List<Map<String, Object>> list = new ArrayList<>();
        for (PaymentMethod method : PaymentMethod.values()) {
            ChannelProfileSnapshot profile = ChannelProfileSnapshot.defaultFor(method);
            list.add(Map.of(
                    "paymentMethod", method.name(),
                    "displayName", method.getDisplayName(),
                    "feeRate", profile.feeRate().toPlainString(),
                    "settlementDayBasis", profile.settlementDayBasis(),
                    "settlementTarget", profile.settlementTarget(),
                    "statementAvailableTime", profile.statementAvailableTime(),
                    "supported", true
            ));
        }
        return list;
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> createAttempt(String paymentNo, PaymentMethod paymentMethod, String requestKey, Long userId) {
        if (requestKey == null || requestKey.isBlank()) {
            throw new IllegalArgumentException("requestKey 不能为空");
        }
        PaymentOrder payment = paymentMapper.selectByPaymentNoForUpdate(paymentNo);
        if (payment == null) {
            throw new IllegalArgumentException("支付单不存在: " + paymentNo);
        }
        TradeOrder order = requireOrder(payment.getOrderNo());
        validateBuyer(order, userId);

        if (!"WAIT_PAY".equals(order.getOrderStatus())) {
            throw new IllegalStateException("订单当前状态不可支付: " + order.getOrderStatus());
        }

        LocalDateTime now = LocalDateTime.now();
        if (payment.getExpireTime() != null && now.isAfter(payment.getExpireTime())) {
            throw new IllegalStateException("支付已超时");
        }

        // 幂等检查：同 request_key 存在则直接返回
        PaymentAttempt existingByKey = attemptMapper.selectOne(new LambdaQueryWrapper<PaymentAttempt>()
                .eq(PaymentAttempt::getPaymentNo, paymentNo)
                .eq(PaymentAttempt::getRequestKey, requestKey));
        if (existingByKey != null) {
            return toAttemptDTO(existingByKey, null);
        }

        // 检查当前活动尝试
        String activeAttemptNo = payment.getActiveAttemptNo();
        if (activeAttemptNo != null && !activeAttemptNo.isBlank()) {
            PaymentAttempt activeAttempt = attemptMapper.selectByAttemptNo(activeAttemptNo);
            if (activeAttempt != null && "PENDING".equals(activeAttempt.getStatus())) {
                if (activeAttempt.getPaymentMethod().equals(paymentMethod.name())) {
                    return toAttemptDTO(activeAttempt, null);
                } else {
                    // 用户切换渠道：尝试关闭旧渠道尝试，支持优雅切换
                    log.info("Switching payment channel from {} to {} for order {}",
                            activeAttempt.getPaymentMethod(), paymentMethod, order.getOrderNo());
                    try {
                        PaymentChannelStrategy oldStrategy = strategyRegistry.require(
                                PaymentMethod.valueOf(activeAttempt.getPaymentMethod()));
                        ChannelProfileSnapshot oldProfile = ChannelProfileSnapshot.defaultFor(
                                PaymentMethod.valueOf(activeAttempt.getPaymentMethod()));
                        ClosePaymentResult closeResult = oldStrategy.closePayment(new CloseChannelPayment(
                                activeAttempt.getAttemptNo(), activeAttempt.getOutTradeNo(),
                                activeAttempt.getChannelTradeNo(), oldProfile));
                        if (closeResult.closed()) {
                            activeAttempt.setStatus("CLOSED");
                            activeAttempt.setUpdatedTime(now);
                            attemptMapper.updateById(activeAttempt);
                            paymentMapper.clearActiveAttempt(paymentNo, activeAttempt.getAttemptNo(), now);
                        } else {
                            // 旧渠道已成功，则直接抛出提示
                            throw new IllegalStateException("上一笔支付正在处理或已扣款，请稍后刷新");
                        }
                    } catch (Exception e) {
                        log.warn("Failed to close old attempt {}, abandoning for new channel: {}",
                                activeAttempt.getAttemptNo(), e.getMessage());
                        activeAttempt.setStatus("CLOSED");
                        activeAttempt.setUpdatedTime(now);
                        attemptMapper.updateById(activeAttempt);
                        paymentMapper.clearActiveAttempt(paymentNo, activeAttempt.getAttemptNo(), now);
                    }
                }
            }
        }

        int nextSeq = attemptMapper.getMaxAttemptSeq(paymentNo) + 1;
        String attemptNo = "PA_" + order.getOrderNo() + "_" + String.format("%02d", nextSeq);
        ChannelProfileSnapshot profile = ChannelProfileSnapshot.defaultFor(paymentMethod);

        PaymentAttempt attempt = PaymentAttempt.builder()
                .attemptNo(attemptNo)
                .paymentNo(paymentNo)
                .orderNo(order.getOrderNo())
                .attemptSeq(nextSeq)
                .paymentMethod(paymentMethod.name())
                .profileId(profile.profileId())
                .profileVersion(profile.version())
                .profileSnapshot("{\"profileId\":\"" + profile.profileId() + "\",\"method\":\"" + paymentMethod + "\"}")
                .channelMerchantNo(profile.channelMerchantNo())
                .outTradeNo(attemptNo)
                .amount(order.getOrderAmount())
                .currency("CNY")
                .status("PENDING")
                .requestKey(requestKey)
                .expiresAt(payment.getExpireTime())
                .nextQueryAt(now.plusSeconds(30))
                .allocationStatus("ORDER_APPLIED")
                .version(0L)
                .createdTime(now)
                .updatedTime(now)
                .build();
        attemptMapper.insert(attempt);

        paymentMapper.bindActiveAttempt(paymentNo, attemptNo, paymentMethod.name(), now);

        // 调用策略创建模拟收银台
        PaymentChannelStrategy strategy = strategyRegistry.require(paymentMethod);
        CreatePaymentResult createResult = strategy.createPayment(new CreateChannelPayment(
                attemptNo,
                paymentNo,
                order.getOrderNo(),
                order.getOrderAmount(),
                "CNY",
                profile,
                requestKey,
                "127.0.0.1"
        ));

        if (createResult.channelTradeNo() != null) {
            attempt.setChannelTradeNo(createResult.channelTradeNo());
            attemptMapper.updateById(attempt);
        }

        return toAttemptDTO(attempt, createResult.cashierAction());
    }

    public Map<String, Object> mockConfirm(String attemptNo, String result, Long userId) {
        PaymentAttempt attempt = requireAttempt(attemptNo);
        TradeOrder order = requireOrder(attempt.getOrderNo());
        validateBuyer(order, userId);

        PaymentMethod method = PaymentMethod.valueOf(attempt.getPaymentMethod());
        ChannelProfileSnapshot profile = ChannelProfileSnapshot.defaultFor(method);

        ChannelPaymentResult payResult = mockChannelEngine.processConfirmPayment(
                attemptNo,
                method,
                profile,
                attempt.getAmount(),
                result
        );

        // 如果模拟支付成功，生成已验证事件并立即触发确认编排
        if ("SUCCESS".equals(payResult.status())) {
            VerifiedChannelEvent event = new VerifiedChannelEvent(
                    "EVT_DIRECT_" + UUID.randomUUID().toString().replace("-", ""),
                    profile.profileId(),
                    profile.channelMerchantNo(),
                    attemptNo,
                    null,
                    payResult.channelTradeNo(),
                    "PAYMENT",
                    payResult.amount(),
                    payResult.feeAmount(),
                    payResult.currency(),
                    payResult.channelSuccessAt(),
                    payResult.channelBusinessDate(),
                    payResult.status(),
                    MockChannelEngine.sha256(attemptNo),
                    "{}"
            );
            confirmationService.acceptVerifiedFact(event);
        } else {
            attempt.setStatus("FAILED");
            attempt.setFailureCode("USER_CANCELLED_OR_FAILED");
            attempt.setUpdatedTime(LocalDateTime.now());
            attemptMapper.updateById(attempt);
            paymentMapper.clearActiveAttempt(attempt.getPaymentNo(), attemptNo, LocalDateTime.now());
        }

        return Map.of(
                "attemptNo", attemptNo,
                "status", payResult.status(),
                "channelTradeNo", payResult.channelTradeNo() != null ? payResult.channelTradeNo() : "",
                "message", "SUCCESS".equals(payResult.status()) ? "支付确认已受理" : "支付已取消或失败"
        );
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> closeAttempt(String attemptNo, String requestKey, Long userId) {
        PaymentAttempt attempt = requireAttempt(attemptNo);
        TradeOrder order = requireOrder(attempt.getOrderNo());
        validateBuyer(order, userId);

        PaymentMethod method = PaymentMethod.valueOf(attempt.getPaymentMethod());
        PaymentChannelStrategy strategy = strategyRegistry.require(method);
        ChannelProfileSnapshot profile = ChannelProfileSnapshot.defaultFor(method);

        ClosePaymentResult closeResult = strategy.closePayment(new CloseChannelPayment(
                attemptNo, attempt.getOutTradeNo(), attempt.getChannelTradeNo(), profile));

        LocalDateTime now = LocalDateTime.now();
        if (closeResult.closed()) {
            attempt.setStatus("CLOSED");
            attempt.setUpdatedTime(now);
            attemptMapper.updateById(attempt);
            paymentMapper.clearActiveAttempt(attempt.getPaymentNo(), attemptNo, now);
        }

        return Map.of(
                "attemptNo", attemptNo,
                "closed", closeResult.closed(),
                "channelStatus", closeResult.channelStatus(),
                "message", closeResult.message()
        );
    }

    public Map<String, Object> getAttemptDetail(String attemptNo, Long userId) {
        PaymentAttempt attempt = requireAttempt(attemptNo);
        TradeOrder order = requireOrder(attempt.getOrderNo());
        validateOrderVisible(order, userId);

        PaymentOrder payment = paymentMapper.selectByPaymentNo(attempt.getPaymentNo());

        Map<String, Object> map = new HashMap<>();
        map.put("attemptNo", attempt.getAttemptNo());
        map.put("paymentNo", attempt.getPaymentNo());
        map.put("orderNo", attempt.getOrderNo());
        map.put("paymentMethod", attempt.getPaymentMethod());
        map.put("amount", attempt.getAmount().toPlainString());
        map.put("currency", attempt.getCurrency());
        map.put("status", attempt.getStatus());
        map.put("channelTradeNo", attempt.getChannelTradeNo() != null ? attempt.getChannelTradeNo() : "");
        map.put("orderStatus", order.getOrderStatus());
        map.put("paymentStatus", payment != null ? payment.getStatus() : "");
        map.put("successfulAttemptNo", payment != null ? payment.getSuccessfulAttemptNo() : null);
        return map;
    }

    private PaymentOrder requirePayment(String paymentNo) {
        PaymentOrder payment = paymentMapper.selectOne(new LambdaQueryWrapper<PaymentOrder>()
                .eq(PaymentOrder::getPaymentNo, paymentNo));
        if (payment == null) {
            throw new IllegalArgumentException("支付单不存在: " + paymentNo);
        }
        return payment;
    }

    private TradeOrder requireOrder(String orderNo) {
        TradeOrder order = orderMapper.selectOne(new LambdaQueryWrapper<TradeOrder>()
                .eq(TradeOrder::getOrderNo, orderNo));
        if (order == null) {
            throw new IllegalArgumentException("订单不存在: " + orderNo);
        }
        return order;
    }

    @Transactional(rollbackFor = Exception.class)
    public String processChannelRefund(String orderNo, BigDecimal amount, String reason) {
        PaymentOrder payment = paymentMapper.selectOne(new LambdaQueryWrapper<PaymentOrder>()
                .eq(PaymentOrder::getOrderNo, orderNo));
        if (payment == null || payment.getSuccessfulAttemptNo() == null) {
            return null;
        }
        PaymentAttempt attempt = attemptMapper.selectByAttemptNo(payment.getSuccessfulAttemptNo());
        if (attempt == null) {
            return null;
        }
        String refundNo = "RF_" + orderNo + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        PaymentMethod method = PaymentMethod.valueOf(attempt.getPaymentMethod());
        ChannelProfileSnapshot profile = ChannelProfileSnapshot.defaultFor(method);

        PaymentRefundOrder refundOrder = PaymentRefundOrder.builder()
                .refundNo(refundNo)
                .originalAttemptNo(attempt.getAttemptNo())
                .orderNo(orderNo)
                .amount(amount)
                .currency("CNY")
                .reason(reason)
                .status("PROCESSING")
                .requestKey("REF_REQ_" + refundNo)
                .acceptedAt(LocalDateTime.now())
                .nextQueryAt(LocalDateTime.now().plusSeconds(60))
                .version(0L)
                .createdTime(LocalDateTime.now())
                .updatedTime(LocalDateTime.now())
                .build();
        refundOrderMapper.insert(refundOrder);

        PaymentChannelStrategy strategy = strategyRegistry.require(method);
        RefundChannelPayment command = new RefundChannelPayment(
                refundNo,
                attempt.getAttemptNo(),
                attempt.getChannelTradeNo(),
                amount,
                "CNY",
                reason,
                profile
        );
        ChannelRefundResult result = strategy.refund(command);
        if ("SUCCEEDED".equals(result.status()) || "PROCESSING".equals(result.status())) {
            refundOrder.setStatus(result.status());
            refundOrder.setChannelRefundNo(result.channelRefundNo());
            refundOrder.setSuccessAt(result.successAt() != null
                    ? LocalDateTime.ofInstant(result.successAt(), profile.getZone())
                    : LocalDateTime.now());
            refundOrder.setUpdatedTime(LocalDateTime.now());
            refundOrderMapper.updateById(refundOrder);

            financialEventMapper.insert(PaymentFinancialEvent.builder()
                    .eventNo("FIN_REF_" + refundNo)
                    .profileId(profile.profileId())
                    .profileVersion(profile.version())
                    .channelMerchantNo(profile.channelMerchantNo())
                    .accountId(profile.accountId())
                    .eventType("REFUND")
                    .eventStage("SUCCESS")
                    .bizType("REFUND")
                    .bizNo(refundNo)
                    .orderNo(orderNo)
                    .amount(amount)
                    .feeAmount(result.feeReturnAmount() != null ? result.feeReturnAmount() : BigDecimal.ZERO)
                    .currency("CNY")
                    .channelBusinessDate(result.channelBusinessDate() != null ? result.channelBusinessDate() : LocalDate.now())
                    .occurredAt(result.successAt() != null ? LocalDateTime.ofInstant(result.successAt(), profile.getZone()) : LocalDateTime.now())
                    .recordedAt(LocalDateTime.now())
                    .createdTime(LocalDateTime.now())
                    .build());
        } else {
            refundOrder.setStatus("FAILED");
            refundOrder.setFailureCode(result.failureCode());
            refundOrder.setUpdatedTime(LocalDateTime.now());
            refundOrderMapper.updateById(refundOrder);
            throw new IllegalStateException("渠道退款失败: " + result.failureCode());
        }
        return refundNo;
    }

    private PaymentAttempt requireAttempt(String attemptNo) {
        PaymentAttempt attempt = attemptMapper.selectByAttemptNo(attemptNo);
        if (attempt == null) {
            throw new IllegalArgumentException("支付尝试不存在: " + attemptNo);
        }
        return attempt;
    }

    private void validateBuyer(TradeOrder order, Long userId) {
        if (userId == null || !userId.equals(order.getBuyerId())) {
            throw new IllegalArgumentException("只有买家可以发起支付或操作收银台");
        }
    }

    private void validateOrderVisible(TradeOrder order, Long userId) {
        if (userId == null || (!userId.equals(order.getBuyerId()) && !userId.equals(order.getSellerId()))) {
            throw new IllegalArgumentException("无权查看该订单支付信息");
        }
    }

    private Map<String, Object> toAttemptDTO(PaymentAttempt attempt, CashierAction action) {
        Map<String, Object> map = new HashMap<>();
        map.put("attemptNo", attempt.getAttemptNo());
        map.put("paymentNo", attempt.getPaymentNo());
        map.put("orderNo", attempt.getOrderNo());
        map.put("paymentMethod", attempt.getPaymentMethod());
        map.put("amount", attempt.getAmount().toPlainString());
        map.put("currency", attempt.getCurrency());
        map.put("status", attempt.getStatus());
        map.put("mode", "MOCK");
        if (action != null) {
            map.put("cashierAction", Map.of(
                    "type", action.type(),
                    "displayToken", action.displayToken(),
                    "payUrl", action.payUrl() != null ? action.payUrl() : "",
                    "instructions", action.instructions() != null ? action.instructions() : ""
            ));
        }
        return map;
    }
}
