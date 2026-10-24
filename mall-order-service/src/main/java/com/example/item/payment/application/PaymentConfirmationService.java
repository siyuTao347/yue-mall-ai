package com.example.item.payment.application;

import api.trade.FundDubboService;
import api.trade.FundOperationResult;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.item.entity.PaymentOrder;
import com.example.item.entity.TradeOrder;
import com.example.item.mapper.OrderMapper;
import com.example.item.mapper.PaymentOrderMapper;
import com.example.item.mapper.TradeOrderMapper;
import com.example.item.payment.channel.ChannelPaymentCommands.*;
import com.example.item.payment.channel.ChannelProfileSnapshot;
import com.example.item.payment.channel.PaymentChannelStrategy;
import com.example.item.payment.channel.PaymentStrategyRegistry;
import com.example.item.payment.domain.*;
import com.example.item.payment.mapper.PaymentAttemptMapper;
import com.example.item.payment.mapper.PaymentFinancialEventMapper;
import com.example.item.payment.mapper.PaymentRefundOrderMapper;
import com.example.item.service.TradeOrchestrationService;
import com.example.item.service.TradeStatusLogService;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;

@Service
@Slf4j
public class PaymentConfirmationService {

    private final PaymentAttemptMapper attemptMapper;
    private final PaymentOrderMapper paymentMapper;
    private final TradeOrderMapper tradeOrderMapper;
    private final OrderMapper legacyOrderMapper;
    private final PaymentFinancialEventMapper financialEventMapper;
    private final PaymentRefundOrderMapper refundOrderMapper;
    private final PaymentStrategyRegistry strategyRegistry;
    private final TradeOrchestrationService orchestrationService;
    private final TradeStatusLogService statusLogService;
    private final TransactionTemplate transactionTemplate;

    @DubboReference(timeout = 5000, retries = 0, check = false)
    private FundDubboService fundService;

    public PaymentConfirmationService(
            PaymentAttemptMapper attemptMapper,
            PaymentOrderMapper paymentMapper,
            TradeOrderMapper tradeOrderMapper,
            OrderMapper legacyOrderMapper,
            PaymentFinancialEventMapper financialEventMapper,
            PaymentRefundOrderMapper refundOrderMapper,
            PaymentStrategyRegistry strategyRegistry,
            @Lazy TradeOrchestrationService orchestrationService,
            TradeStatusLogService statusLogService,
            TransactionTemplate transactionTemplate
    ) {
        this.attemptMapper = attemptMapper;
        this.paymentMapper = paymentMapper;
        this.tradeOrderMapper = tradeOrderMapper;
        this.legacyOrderMapper = legacyOrderMapper;
        this.financialEventMapper = financialEventMapper;
        this.refundOrderMapper = refundOrderMapper;
        this.strategyRegistry = strategyRegistry;
        this.orchestrationService = orchestrationService;
        this.statusLogService = statusLogService;
        this.transactionTemplate = transactionTemplate;
    }

    public String acceptVerifiedFact(VerifiedChannelEvent event) {
        String attemptNo = event.attemptNo();
        if (attemptNo == null || attemptNo.isBlank()) {
            log.warn("VerifiedChannelEvent missing attemptNo, eventId={}", event.eventId());
            return "IGNORED_MISSING_ATTEMPT";
        }

        PaymentAttempt attempt = attemptMapper.selectByAttemptNo(attemptNo);
        if (attempt == null) {
            log.warn("PaymentAttempt not found for attemptNo={}, eventId={}", attemptNo, event.eventId());
            return "ATTEMPT_NOT_FOUND";
        }

        if ("SUCCEEDED".equals(attempt.getStatus())) {
            return "ALREADY_SUCCEEDED";
        }

        PaymentOrder payment = paymentMapper.selectByPaymentNo(attempt.getPaymentNo());
        if (payment == null) {
            log.error("PaymentOrder not found for paymentNo={}", attempt.getPaymentNo());
            return "PAYMENT_ORDER_NOT_FOUND";
        }

        TradeOrder order = tradeOrderMapper.selectOne(new LambdaQueryWrapper<TradeOrder>()
                .eq(TradeOrder::getOrderNo, attempt.getOrderNo()));
        if (order == null) {
            log.error("TradeOrder not found for orderNo={}", attempt.getOrderNo());
            return "ORDER_NOT_FOUND";
        }

        LocalDateTime now = LocalDateTime.now();
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        LocalDateTime channelTime = event.occurredAt() != null ? LocalDateTime.ofInstant(event.occurredAt(), zone) : now;

        // 判断订单是否能够正常采用该笔支付
        boolean canApplyToOrder = "WAIT_PAY".equals(order.getOrderStatus())
                && order.getOrderAmount().compareTo(event.amount()) == 0
                && (payment.getSuccessfulAttemptNo() == null || payment.getSuccessfulAttemptNo().isBlank());

        if (canApplyToOrder) {
            // 正常订单履约路径
            return transactionTemplate.execute(status -> {
                // 1. 更新尝试表
                attempt.setStatus("SUCCEEDED");
                attempt.setChannelTradeNo(event.channelTradeNo());
                attempt.setChannelSuccessAt(channelTime);
                attempt.setChannelBusinessDate(event.channelBusinessDate());
                attempt.setLocalConfirmedAt(now);
                attempt.setAllocationStatus("ORDER_APPLIED");
                attempt.setUpdatedTime(now);
                attemptMapper.updateById(attempt);

                // 2. 绑定聚合单
                paymentMapper.bindSuccessfulAttempt(payment.getPaymentNo(), attempt.getAttemptNo(),
                        attempt.getPaymentMethod(), now);

                // 3. 产生平台侧不可变支付金融事件
                recordFinancialEvent(attempt, "PAYMENT", "OCCURRED", "ORDER", attempt.getAmount(),
                        event.feeAmount(), channelTime, event.channelBusinessDate());

                // 4. 推进订单状态为 PAY_CONFIRMING
                int orderUpdated = tradeOrderMapper.markPayConfirming(order.getOrderNo(), now);
                if (orderUpdated <= 0) {
                    log.warn("Order {} cannot be marked PAY_CONFIRMING, likely processed concurrently", order.getOrderNo());
                }

                statusLogService.log(order.getOrderNo(), "WAIT_PAY", "PAY_CONFIRMING", "ORDER",
                        "SYSTEM", null, "渠道支付成功，开始编排平台确认");
                statusLogService.log(order.getOrderNo(), "INIT", "SUCCESS_PENDING", "PAY",
                        "SYSTEM", null, "渠道流水: " + event.channelTradeNo());

                // 5. 触发支付确认任务编排
                orchestrationService.createPaymentConfirmTask(order, payment.getPaymentNo());
                return "PAYMENT_CONFIRMED";
            });
        } else {
            // 迟到支付或重复支付，进入异常资金挂账 (Payment Suspense) 并自动原路退回
            log.warn("Late/Excess payment detected for attemptNo={}, orderNo={}, orderStatus={}",
                    attemptNo, order.getOrderNo(), order.getOrderStatus());

            transactionTemplate.executeWithoutResult(status -> {
                attempt.setStatus("SUCCEEDED");
                attempt.setChannelTradeNo(event.channelTradeNo());
                attempt.setChannelSuccessAt(channelTime);
                attempt.setChannelBusinessDate(event.channelBusinessDate());
                attempt.setLocalConfirmedAt(now);
                attempt.setAllocationStatus("LATE_PAYMENT");
                attempt.setUpdatedTime(now);
                attemptMapper.updateById(attempt);

                // 记挂账金融事件
                recordFinancialEvent(attempt, "PAYMENT", "OCCURRED", "SUSPENSE", attempt.getAmount(),
                        event.feeAmount(), channelTime, event.channelBusinessDate());
            });

            // 异步调用 User 服务入账挂账账户
            try {
                if (fundService != null) {
                    fundService.creditPaymentSuspense(attemptNo, attempt.getAmount(), "FUND_SUSPENSE_CREDIT:" + attemptNo);
                }
            } catch (Exception e) {
                log.error("Failed to credit payment suspense in user service for attemptNo={}", attemptNo, e);
            }

            // 自动原路退款给用户，不复活已关订单
            triggerSuspenseAutoRefund(attempt, event);

            return "PAYMENT_SUSPENSED";
        }
    }

    private void recordFinancialEvent(
            PaymentAttempt attempt,
            String eventType,
            String eventStage,
            String bizType,
            BigDecimal amount,
            BigDecimal feeAmount,
            LocalDateTime occurredAt,
            java.time.LocalDate businessDate
    ) {
        String eventNo = "FE_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase();
        PaymentFinancialEvent fe = PaymentFinancialEvent.builder()
                .eventNo(eventNo)
                .profileId(attempt.getProfileId())
                .profileVersion(attempt.getProfileVersion())
                .channelMerchantNo(attempt.getChannelMerchantNo())
                .accountId("ACC_MAIN")
                .eventType(eventType)
                .eventStage(eventStage)
                .bizType(bizType)
                .bizNo(attempt.getAttemptNo())
                .orderNo(attempt.getOrderNo())
                .amount(amount)
                .feeAmount(feeAmount != null ? feeAmount : BigDecimal.ZERO)
                .currency(attempt.getCurrency())
                .channelBusinessDate(businessDate != null ? businessDate : occurredAt.toLocalDate())
                .occurredAt(occurredAt)
                .recordedAt(LocalDateTime.now())
                .createdTime(LocalDateTime.now())
                .build();
        try {
            financialEventMapper.insert(fe);
        } catch (Exception e) {
            log.warn("Failed to insert financial event (might be duplicate): {}", e.getMessage());
        }
    }

    private void triggerSuspenseAutoRefund(PaymentAttempt attempt, VerifiedChannelEvent event) {
        String refundNo = "REF_SUSPENSE_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
        LocalDateTime now = LocalDateTime.now();

        PaymentRefundOrder refundOrder = PaymentRefundOrder.builder()
                .refundNo(refundNo)
                .originalAttemptNo(attempt.getAttemptNo())
                .orderNo(attempt.getOrderNo())
                .amount(attempt.getAmount())
                .currency(attempt.getCurrency())
                .reason("迟到/重复支付挂账自动原路退回")
                .status("SUBMITTING")
                .requestKey("REQ_REF_" + refundNo)
                .createdTime(now)
                .updatedTime(now)
                .build();
        refundOrderMapper.insert(refundOrder);

        try {
            PaymentMethod method = PaymentMethod.valueOf(attempt.getPaymentMethod());
            PaymentChannelStrategy strategy = strategyRegistry.require(method);
            ChannelProfileSnapshot profile = ChannelProfileSnapshot.defaultFor(method);

            RefundChannelPayment refundCmd = new RefundChannelPayment(
                    refundNo,
                    attempt.getAttemptNo(),
                    attempt.getChannelTradeNo(),
                    attempt.getAmount(),
                    attempt.getCurrency(),
                    "挂账原路退回",
                    profile
            );
            ChannelRefundResult result = strategy.refund(refundCmd);

            if (result.success()) {
                refundOrder.setStatus("SUCCEEDED");
                refundOrder.setChannelRefundNo(result.channelRefundNo());
                refundOrder.setSuccessAt(now);
                refundOrder.setUpdatedTime(now);
                refundOrderMapper.updateById(refundOrder);

                if (fundService != null) {
                    fundService.refundPaymentSuspense(attempt.getAttemptNo(), refundNo, attempt.getAmount(),
                            "FUND_SUSPENSE_REFUND:" + refundNo);
                }
                log.info("Suspense refund succeeded for attemptNo={}, refundNo={}", attempt.getAttemptNo(), refundNo);
            }
        } catch (Exception e) {
            log.error("Failed to execute suspense refund for attemptNo={}", attempt.getAttemptNo(), e);
        }
    }
}
