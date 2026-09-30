package com.example.item.service;

import api.risk.RiskDecisionResult;
import api.risk.RiskEvaluateRequest;
import api.risk.RiskSupport;
import api.trade.AssetDubboService;
import api.trade.AssetReservationResult;
import api.trade.FundDubboService;
import api.trade.FundOperationResult;
import api.trade.ItemSnapshotDTO;
import api.trade.MerchantDubboService;
import com.example.item.config.PaymentCallbackProperties;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.item.config.TradeOrchestrationProperties;
import com.example.item.dto.TradeOrchestrationContext;
import com.example.item.entity.Arbitration;
import com.example.item.entity.Dispute;
import com.example.item.entity.OrderSettlement;
import com.example.item.entity.PaymentOrder;
import com.example.item.entity.TradeOrder;
import com.example.item.entity.TradeOrchestrationStep;
import com.example.item.entity.TradeOrchestrationTask;
import com.example.item.mapper.ArbitrationMapper;
import com.example.item.mapper.DisputeMapper;
import com.example.item.mapper.OrderSettlementMapper;
import com.example.item.mapper.TradeOrderMapper;
import com.example.item.mapper.PaymentOrderMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Map;

@Service
@Slf4j
public class TradeOrchestrationCommandService {
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final TradeOrderMapper orderMapper;
    private final PaymentOrderMapper paymentMapper;
    private final OrderSettlementMapper settlementMapper;
    private final ArbitrationMapper arbitrationMapper;
    private final DisputeMapper disputeMapper;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;
    private final TradeStatusLogService statusLogService;
    private final TradeOrchestrationProperties properties;
    private final RiskClient riskClient;
    private final PaymentCallbackProperties paymentCallbackProperties;

    @DubboReference(timeout = 5000, retries = 0, check = false)
    private FundDubboService fundService;
    @DubboReference(timeout = 5000, retries = 0, check = false)
    private AssetDubboService assetService;
    @DubboReference(timeout = 5000, retries = 0, check = false)
    private MerchantDubboService merchantService;

    public TradeOrchestrationCommandService(
            TradeOrderMapper orderMapper,
            PaymentOrderMapper paymentMapper,
            OrderSettlementMapper settlementMapper,
            ArbitrationMapper arbitrationMapper,
            DisputeMapper disputeMapper,
            TransactionTemplate transactionTemplate,
            ObjectMapper objectMapper,
            TradeStatusLogService statusLogService,
            TradeOrchestrationProperties properties,
            RiskClient riskClient,
            PaymentCallbackProperties paymentCallbackProperties
    ) {
        this.orderMapper = orderMapper;
        this.paymentMapper = paymentMapper;
        this.settlementMapper = settlementMapper;
        this.arbitrationMapper = arbitrationMapper;
        this.disputeMapper = disputeMapper;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
        this.statusLogService = statusLogService;
        this.properties = properties;
        this.riskClient = riskClient;
        this.paymentCallbackProperties = paymentCallbackProperties;
    }

    public String execute(TradeOrchestrationTask task, TradeOrchestrationStep step) {
        TradeOrchestrationContext context = context(task);
        return switch (step.getStepName()) {
            case "RISK_PRECHECK" -> riskPrecheck(context);
            case "ASSET_RESERVE" -> assetReserve(context);
            case "ORDER_READY" -> orderReady(context);
            case "RISK_CONFIRM" -> riskConfirm(context);
            case "RISK_RECORD_EVENT" -> riskRecordEvent(context);
            case "RISK_CONFIRM_EVENT" -> riskConfirmEvent(context);
            case "FUND_FREEZE" -> fundFreeze(context);
            case "ASSET_CONFIRM" -> assetConfirm(context);
            case "LOCAL_PAYMENT_CONFIRM" -> localPaymentConfirm(context);
            case "ESCROW_CONFIRM" -> escrowConfirm(context);
            case "FUND_SETTLE" -> fundSettle(context);
            case "SAVE_SETTLEMENT" -> saveSettlement(context);
            case "SETTLE_AVAILABLE" -> settleAvailable(context);
            case "MARK_SETTLED" -> markSettled(context);
            case "FUND_REFUND" -> fundRefund(context);
            case "ASSET_INVALIDATE" -> assetInvalidate(context);
            case "MERCHANT_REFUND" -> merchantRefund(context);
            case "MERCHANT_DISPUTE" -> merchantDispute(context);
            case "MERCHANT_COMPLETE" -> merchantComplete(context);
            case "LOCAL_ARBITRATION_REFUND" -> localArbitrationRefund(context);
            case "LOCAL_ARBITRATION_RESOLVE" -> localArbitrationResolve(context);
            case "ASSET_RELEASE" -> assetRelease(context);
            case "LOCAL_CANCELLED" -> localCancelled(context);
            default -> throw new IllegalArgumentException("未知编排步骤: " + step.getStepName());
        };
    }

    public TradeOrchestrationContext settlementContext(TradeOrchestrationContext source,
                                                       BigDecimal feeRatePercent,
                                                       BigDecimal minFee) {
        BigDecimal orderAmount = requireAmount(source.orderAmount());
        if (orderAmount.compareTo(minFee) <= 0) {
            throw new IllegalArgumentException("订单金额必须大于最低手续费");
        }
        BigDecimal fee = orderAmount.multiply(feeRatePercent)
                .divide(HUNDRED, 2, RoundingMode.HALF_UP)
                .max(minFee)
                .min(orderAmount);
        return source.withSettlementAmounts(fee, orderAmount.subtract(fee));
    }

    private String riskPrecheck(TradeOrchestrationContext context) {
        RiskDecisionResult decision = riskClient.evaluate(buildRiskRequest(context));
        String riskStatus = RiskSupport.toRiskStatus(decision.getAction());
        orderMapper.markCreateRiskDecision(
                context.orderNo(),
                riskStatus,
                decision.getRiskLevel() == null ? "LOW" : decision.getRiskLevel(),
                decision.getDecisionNo(),
                decision.getMessage(),
                LocalDateTime.now()
        );
        if (RiskDecisionResult.ACTION_REJECT.equals(decision.getAction())) {
            statusLogService.log(context.orderNo(), "CREATE_PENDING", "REJECTED", "ORDER",
                    "RISK", null, decision.getMessage());
            throw new TradeOrchestrationTerminalException("ORDER_REJECTED");
        }
        return decision.getDecisionNo();
    }

    private String assetReserve(TradeOrchestrationContext context) {
        AssetReservationResult reservation = assetService.reserve(
                context.itemId(), context.quantity(), context.orderNo(), properties.paymentExpireMinutes(),
                "ASSET_RESERVE:" + context.orderNo());
        if (!reservation.isSuccess()) {
            orderMapper.markCreateClosed(context.orderNo(), LocalDateTime.now());
            statusLogService.log(context.orderNo(), "CREATE_PENDING", "CLOSED", "ORDER",
                    "ASSET", null, reservation.getMessage());
            throw new TradeOrchestrationTerminalException("ORDER_ASSET_UNAVAILABLE");
        }
        ItemSnapshotDTO snapshot = reservation.getItemSnapshot();
        BigDecimal orderAmount = snapshot.getPrice().multiply(BigDecimal.valueOf(context.quantity()));
        if (orderAmount.compareTo(properties.minFee()) <= 0) {
            releaseCreateReservation(context.orderNo());
            throw new TradeOrchestrationTerminalException("ORDER_AMOUNT_INVALID");
        }
        if (context.buyerId().equals(snapshot.getSellerId())) {
            releaseCreateReservation(context.orderNo());
            throw new TradeOrchestrationTerminalException("ORDER_BUYER_SELLER_SAME");
        }
        LocalDateTime now = LocalDateTime.now();
        int updated = orderMapper.completeCreateReservation(
                context.orderNo(),
                snapshot.getSellerId(),
                snapshot.getMerchantId(),
                writeJson(snapshot),
                orderAmount,
                now.plusMinutes(properties.paymentExpireMinutes()),
                now
        );
        if (updated <= 0) {
            TradeOrder order = orderMapper.selectByOrderNoForUpdate(context.orderNo());
            if (order == null || !isCreateInProgress(order)) {
                throw new IllegalStateException("订单创建状态变化，资产预留结果保存失败");
            }
        }
        statusLogService.log(context.orderNo(), "CREATE_PENDING", "CREATE_PENDING", "ASSET",
                "ASSET", null, "资产预留完成");
        return reservation.getReservationNo();
    }

    private void releaseCreateReservation(String orderNo) {
        if (!assetService.release(orderNo, "ASSET_RELEASE:" + orderNo)) {
            throw new IllegalStateException("订单创建失败后的资产释放失败");
        }
        orderMapper.markCreateClosed(orderNo, LocalDateTime.now());
        statusLogService.log(orderNo, "CREATE_PENDING", "CLOSED", "ORDER",
                "ASSET", null, "订单创建失败，资产预留已释放");
    }

    private boolean isCreateInProgress(TradeOrder order) {
        return "CREATE_PENDING".equals(order.getOrderStatus())
                || "WAIT_PAY".equals(order.getOrderStatus());
    }

    private String orderReady(TradeOrchestrationContext context) {
        LocalDateTime now = LocalDateTime.now();
        transactionTemplate.executeWithoutResult(status -> {
            TradeOrder order = orderMapper.selectByOrderNoForUpdate(context.orderNo());
            if (order == null || !isCreateInProgress(order)) {
                throw new TradeOrchestrationTerminalException("ORDER_CREATE_INTERRUPTED");
            }
            PaymentOrder existingPayment = paymentMapper.selectOne(new LambdaQueryWrapper<PaymentOrder>()
                    .eq(PaymentOrder::getOrderNo, context.orderNo()));
            if (existingPayment == null) {
                paymentMapper.insert(buildPayment(order, now));
            }
            orderMapper.markCreateReady(context.orderNo(), "PAY" + context.orderNo(), now);
        });
        statusLogService.log(context.orderNo(), "CREATE_PENDING", "WAIT_PAY", "ORDER",
                "SYSTEM", context.buyerId(), "订单创建完成，等待支付");
        return "WAIT_PAY";
    }

    private PaymentOrder buildPayment(TradeOrder order, LocalDateTime now) {
        PaymentOrder payment = new PaymentOrder();
        payment.setPaymentNo("PAY" + order.getOrderNo());
        payment.setOrderNo(order.getOrderNo());
        payment.setChannel("MOCK_WECHAT");
        payment.setAmount(order.getOrderAmount());
        payment.setStatus("INIT");
        payment.setCallbackTokenHash(sha256(order.getOrderNo() + order.getOrderAmount()));
        payment.setCallbackSecretVersion(paymentCallbackProperties.secretVersion());
        payment.setExpireTime(order.getPayDeadline());
        payment.setCreatedTime(now);
        payment.setUpdatedTime(now);
        return payment;
    }

    private String riskConfirm(TradeOrchestrationContext context) {
        riskClient.confirmForOrchestration(context.eventNo());
        return "CONFIRMED";
    }

    private String riskRecordEvent(TradeOrchestrationContext context) {
        RiskDecisionResult result = riskClient.recordEventForOrchestration(buildRiskRequest(context));
        return result == null ? "RECORDED" : result.getDecisionNo();
    }

    private String riskConfirmEvent(TradeOrchestrationContext context) {
        riskClient.confirmForOrchestration(context.eventNo());
        return "CONFIRMED";
    }

    private RiskEvaluateRequest buildRiskRequest(TradeOrchestrationContext context) {
        return RiskEvaluateRequest.builder()
                .eventNo(context.eventNo())
                .scene(context.scene())
                .eventType(context.eventType())
                .bizType("ORDER")
                .bizNo(context.orderNo())
                .userId(context.buyerId())
                .merchantId(context.merchantId())
                .itemId(context.itemId())
                .orderNo(context.orderNo())
                .amount(context.orderAmount())
                .ipHash(context.ipHash())
                .deviceHash(context.deviceHash())
                .payload(context.payload())
                .build();
    }

    private String fundFreeze(TradeOrchestrationContext context) {
        FundOperationResult result = fundService.freezeEscrow(
                context.orderNo(), context.sellerId(), context.merchantId(), context.orderAmount(),
                "FUND_FREEZE:" + context.orderNo());
        requireFundSuccess(result);
        return result.getTransactionNo();
    }

    private String assetConfirm(TradeOrchestrationContext context) {
        if (!assetService.confirm(context.orderNo(), "ASSET_CONFIRM:" + context.orderNo())) {
            throw new IllegalStateException("资产确认失败");
        }
        return "CONFIRMED";
    }

    private String localPaymentConfirm(TradeOrchestrationContext context) {
        LocalDateTime now = LocalDateTime.now();
        transactionTemplate.executeWithoutResult(status -> {
            int updated = orderMapper.markPaidFromPayConfirming(
                    context.orderNo(), now, properties.deliveryTimeoutMinutes());
            if (updated <= 0 && !isPaymentAlreadyConfirmed(context)) {
                throw new IllegalStateException("订单支付确认状态变化，任务执行中断");
            }
            paymentMapper.markSuccess(context.paymentNo(), now);
        });
        statusLogService.log(context.orderNo(), "PAY_CONFIRMING", "PAID", "ORDER",
                "SYSTEM", null, "支付回调校验通过，资金和资产确认完成");
        statusLogService.log(context.orderNo(), "SUCCESS_PENDING", "SUCCESS", "PAY",
                "SYSTEM", null, "支付单确认成功");
        return "PAID";
    }

    private boolean isPaymentAlreadyConfirmed(TradeOrchestrationContext context) {
        return orderMapper.selectByOrderNoForUpdate(context.orderNo()) != null
                && "SUCCESS".equals(orderMapper.selectByOrderNoForUpdate(context.orderNo()).getPayStatus());
    }

    private String escrowConfirm(TradeOrchestrationContext context) {
        LocalDateTime now = LocalDateTime.now();
        if (orderMapper.markEscrowFrozen(context.orderNo(), now) <= 0
                && !"FROZEN".equals(orderMapper.selectByOrderNoForUpdate(context.orderNo()).getEscrowStatus())) {
            throw new IllegalStateException("担保资金状态确认失败");
        }
        statusLogService.log(context.orderNo(), "FREEZE_PENDING", "FROZEN", "ESCROW",
                "SYSTEM", null, "担保资金冻结完成");
        return "FROZEN";
    }

    private String fundSettle(TradeOrchestrationContext context) {
        FundOperationResult result = fundService.settle(
                context.orderNo(), context.sellerId(), context.merchantId(),
                context.orderAmount(), context.feeAmount(), context.sellerIncome(),
                "FUND_SETTLE:" + context.orderNo());
        requireFundSuccess(result);
        return result.getTransactionNo();
    }

    private String saveSettlement(TradeOrchestrationContext context) {
        LocalDateTime now = LocalDateTime.now();
        transactionTemplate.executeWithoutResult(status -> {
            OrderSettlement existing = settlementMapper.selectOne(new LambdaQueryWrapper<OrderSettlement>()
                    .eq(OrderSettlement::getOrderNo, context.orderNo()));
            if (existing != null) {
                requireSameSettlement(existing, context);
                if (existing.getFundTransactionNo() == null) {
                    existing.setFundTransactionNo(context.fundTransactionNo());
                    existing.setStatus("SUCCESS");
                    existing.setSettledTime(now);
                    existing.setUpdatedTime(now);
                    settlementMapper.updateById(existing);
                }
                return;
            }
            OrderSettlement settlement = new OrderSettlement();
            settlement.setOrderNo(context.orderNo());
            settlement.setOrderAmount(context.orderAmount());
            settlement.setFeeAmount(context.feeAmount());
            settlement.setSellerIncome(context.sellerIncome());
            settlement.setStatus("SUCCESS");
            settlement.setFundTransactionNo(context.fundTransactionNo());
            settlement.setSettledTime(now);
            settlement.setCreatedTime(now);
            settlement.setUpdatedTime(now);
            settlementMapper.insert(settlement);
            orderMapper.updateFeeAndIncomeByOrderNo(context.orderNo(),
                    context.feeAmount(), context.sellerIncome(), now);
        });
        return "SUCCESS";
    }

    private String settleAvailable(TradeOrchestrationContext context) {
        FundOperationResult result = fundService.settlePendingToAvailable(
                context.orderNo(), context.sellerId(), context.sellerIncome(),
                "FUND_SETTLE_AVAILABLE:" + context.orderNo());
        requireFundSuccess(result);
        return result.getTransactionNo();
    }

    private String markSettled(TradeOrchestrationContext context) {
        LocalDateTime now = LocalDateTime.now();
        if (orderMapper.markSettled(context.orderNo(), now) <= 0
                && !"SETTLED".equals(orderMapper.selectByOrderNoForUpdate(context.orderNo()).getOrderStatus())) {
            throw new IllegalStateException("订单已被其他任务处理，结算中断");
        }
        statusLogService.log(context.orderNo(), "SETTLING", "SETTLED", "ORDER",
                "SYSTEM", null, "结算冷却期结束并放款");
        return "SETTLED";
    }

    private String fundRefund(TradeOrchestrationContext context) {
        FundOperationResult result = fundService.refundEscrow(
                context.orderNo(), context.buyerId(), context.orderAmount(),
                "FUND_REFUND:" + context.orderNo());
        requireFundSuccess(result);
        return result.getTransactionNo();
    }

    private String assetInvalidate(TradeOrchestrationContext context) {
        if (!assetService.invalidateByOrderNo(
                context.orderNo(), "ASSET_INVALIDATE:" + context.orderNo())) {
            throw new IllegalStateException("资产退款处理失败");
        }
        return "INVALID";
    }

    private String merchantRefund(TradeOrchestrationContext context) {
        merchantService.refundOrder(
                context.merchantId(), context.orderNo(), "MERCHANT_REFUND:" + context.orderNo());
        return "SUCCESS";
    }

    private String merchantDispute(TradeOrchestrationContext context) {
        merchantService.disputeOrder(
                context.merchantId(), context.orderNo(), "MERCHANT_DISPUTE:" + context.orderNo());
        return "SUCCESS";
    }

    private String merchantComplete(TradeOrchestrationContext context) {
        if (context.payload() == null || context.payload().get("score") == null) {
            throw new IllegalArgumentException("评价分数缺失");
        }
        BigDecimal score = new BigDecimal(context.payload().get("score").toString());
        if (score.compareTo(BigDecimal.ONE) < 0 || score.compareTo(BigDecimal.valueOf(5)) > 0) {
            throw new IllegalArgumentException("评价分数必须在 1 到 5 之间");
        }
        merchantService.completeOrder(
                context.merchantId(), score, context.orderNo(),
                "MERCHANT_COMPLETE:" + context.orderNo());
        return "SUCCESS";
    }

    private String localArbitrationRefund(TradeOrchestrationContext context) {
        LocalDateTime now = LocalDateTime.now();
        transactionTemplate.executeWithoutResult(status -> {
            int updated = orderMapper.markRefunded(context.orderNo(), now);
            if (updated <= 0 && !isOrderAlreadyRefunded(context)) {
                throw new IllegalStateException("订单退款状态变化，任务执行中断");
            }
        });
        statusLogService.log(context.orderNo(), "REFUNDING", "REFUNDED", "ORDER",
                "SYSTEM", null, nullToDefault(context.reason(), "仲裁或超时退款完成"));
        return "REFUNDED";
    }

    private String localArbitrationResolve(TradeOrchestrationContext context) {
        if (context.disputeNo() == null) {
            return "RESOLVED";
        }
        LocalDateTime now = LocalDateTime.now();
        transactionTemplate.executeWithoutResult(status -> {
            Dispute dispute = disputeMapper.selectOne(new LambdaQueryWrapper<Dispute>()
                    .eq(Dispute::getDisputeNo, context.disputeNo()));
            if (dispute == null) {
                throw new IllegalStateException("售后单不存在，仲裁结果保存失败");
            }
            if (!"RESOLVED".equals(dispute.getStatus())) {
                Arbitration arbitration = buildArbitration(dispute, context);
                arbitrationMapper.insert(arbitration);
                dispute.setStatus("RESOLVED");
                dispute.setUpdatedTime(now);
                disputeMapper.updateById(dispute);
            }
            int updated = orderMapper.markDispute(context.orderNo(), "RESOLVED", now);
            if (updated <= 0) {
                throw new IllegalStateException("订单售后状态更新失败");
            }
        });
        statusLogService.log(context.orderNo(), "ARBITRATING", "RESOLVED", "DISPUTE",
                "SYSTEM", context.adminId(), nullToDefault(context.reason(), "仲裁处理完成"));
        return "RESOLVED";
    }

    private String assetRelease(TradeOrchestrationContext context) {
        if (!assetService.release(context.orderNo(), "ASSET_RELEASE:" + context.orderNo())) {
            throw new IllegalStateException("释放资产预留失败");
        }
        return "RELEASED";
    }

    private String localCancelled(TradeOrchestrationContext context) {
        LocalDateTime now = LocalDateTime.now();
        transactionTemplate.executeWithoutResult(status -> {
            int updated = orderMapper.markCancelled(context.orderNo(), now);
            if (updated <= 0 && !isOrderAlreadyCancelled(context)) {
                throw new IllegalStateException("订单取消状态变化，任务执行中断");
            }
        });
        statusLogService.log(context.orderNo(), "CANCELLING", "CANCELLED", "ORDER",
                "SYSTEM", null, nullToDefault(context.reason(), "订单取消完成"));
        return "CANCELLED";
    }

    private boolean isOrderAlreadyRefunded(TradeOrchestrationContext context) {
        TradeOrder order = orderMapper.selectByOrderNoForUpdate(context.orderNo());
        return order != null && "REFUNDED".equals(order.getOrderStatus());
    }

    private boolean isOrderAlreadyCancelled(TradeOrchestrationContext context) {
        TradeOrder order = orderMapper.selectByOrderNoForUpdate(context.orderNo());
        return order != null && "CANCELLED".equals(order.getOrderStatus());
    }

    private Arbitration buildArbitration(Dispute dispute, TradeOrchestrationContext context) {
        Arbitration arbitration = new Arbitration();
        arbitration.setDisputeNo(dispute.getDisputeNo());
        arbitration.setOrderNo(dispute.getOrderNo());
        arbitration.setResult(context.result());
        arbitration.setRefundAmount("REFUND_ALL".equals(context.result()) ? context.orderAmount() : BigDecimal.ZERO);
        arbitration.setReleaseAmount("RELEASE_ALL".equals(context.result()) ? context.orderAmount() : BigDecimal.ZERO);
        arbitration.setDepositDeductAmount(BigDecimal.ZERO);
        arbitration.setReason(context.reason());
        arbitration.setArbitratorId(context.adminId());
        arbitration.setAppealStatus("NONE");
        arbitration.setCreatedTime(LocalDateTime.now());
        return arbitration;
    }

    private String nullToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private TradeOrchestrationContext context(TradeOrchestrationTask task) {
        try {
            TradeOrchestrationContext context = objectMapper.readValue(
                    task.getContextJson(), TradeOrchestrationContext.class);
            return context == null ? TradeOrchestrationContext.empty() : context;
        } catch (Exception exception) {
            throw new IllegalStateException("编排上下文反序列化失败", exception);
        }
    }

    private void requireSameSettlement(OrderSettlement existing, TradeOrchestrationContext context) {
        if (existing.getOrderAmount().compareTo(context.orderAmount()) != 0
                || existing.getFeeAmount().compareTo(context.feeAmount()) != 0
                || existing.getSellerIncome().compareTo(context.sellerIncome()) != 0) {
            throw new IllegalStateException("重复结算请求金额不一致");
        }
    }

    private void requireFundSuccess(FundOperationResult result) {
        if (result == null || !result.isSuccess()) {
            throw new IllegalStateException(result == null ? "资金服务无响应" : result.getMessage());
        }
    }

    private BigDecimal requireAmount(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("编排金额不合法");
        }
        return amount;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("商品快照序列化失败", exception);
        }
    }

    private String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 计算失败", exception);
        }
    }
}
