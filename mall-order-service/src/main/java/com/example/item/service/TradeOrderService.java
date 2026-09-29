package com.example.item.service;

import api.trade.AssetDubboService;
import api.trade.AssetReservationResult;
import api.trade.CardSecretDTO;
import api.trade.FundDubboService;
import api.trade.FundOperationResult;
import api.trade.ItemSnapshotDTO;
import api.trade.MerchantDubboService;
import api.risk.RiskDecisionResult;
import api.risk.RiskEvaluateRequest;
import api.risk.RiskSupport;
import api.risk.SensitiveWordHitDTO;
import api.risk.SensitiveWordScanner;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.item.entity.Arbitration;
import com.example.item.entity.DeliveryEvidence;
import com.example.item.entity.DeliveryRecord;
import com.example.item.entity.Dispute;
import com.example.item.entity.DisputeMessage;
import com.example.item.entity.OrderReview;
import com.example.item.entity.OrderSettlement;
import com.example.item.entity.PaymentCallback;
import com.example.item.entity.PaymentOrder;
import com.example.item.entity.TradeOrder;
import com.example.item.entity.TradeOrderStatusLog;
import com.example.item.mapper.ArbitrationMapper;
import com.example.item.mapper.DeliveryEvidenceMapper;
import com.example.item.mapper.DeliveryRecordMapper;
import com.example.item.mapper.DisputeMapper;
import com.example.item.mapper.DisputeMessageMapper;
import com.example.item.mapper.OrderReviewMapper;
import com.example.item.mapper.OrderSettlementMapper;
import com.example.item.mapper.PaymentCallbackMapper;
import com.example.item.mapper.PaymentOrderMapper;
import com.example.item.mapper.TradeOrderMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.function.Consumer;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Slf4j
public class TradeOrderService {
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final TradeOrderMapper orderMapper;
    private final PaymentOrderMapper paymentMapper;
    private final PaymentCallbackMapper callbackMapper;
    private final DeliveryRecordMapper deliveryMapper;
    private final DeliveryEvidenceMapper evidenceMapper;
    private final DisputeMapper disputeMapper;
    private final DisputeMessageMapper disputeMessageMapper;
    private final ArbitrationMapper arbitrationMapper;
    private final OrderSettlementMapper settlementMapper;
    private final OrderReviewMapper reviewMapper;
    private final TradeStatusLogService statusLog;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final RiskClient riskClient;
    private final OrderRiskStateWriter riskStateWriter;
    private static final List<String> SECRET_VIEWABLE_ORDER_STATUSES =
            List.of("DELIVERED", "CONFIRMED", "SETTLING", "SETTLED");
    private static final List<String> SECRET_VIEWABLE_ESCROW_STATUSES =
            List.of("FROZEN", "SETTLE_PENDING", "SETTLED");
    private static final List<String> RISK_BLOCKED_STATUSES =
            List.of("MANUAL_REVIEW", "FROZEN", "REJECTED");

    @Value("${trade.fee-rate:2}")
    private BigDecimal feeRatePercent = new BigDecimal("2");
    @Value("${trade.min-fee:0.01}")
    private BigDecimal minFee = new BigDecimal("0.01");
    @Value("${trade.payment-expire-minutes:15}")
    private int paymentExpireMinutes;
    @Value("${trade.delivery-timeout-minutes:30}")
    private int deliveryTimeoutMinutes;
    @Value("${trade.auto-confirm-hours:24}")
    private int autoConfirmHours;
    @Value("${trade.settle-cooldown-hours:24}")
    private int settleCooldownHours;

    @DubboReference(timeout = 5000, retries = 0, check = false)
    private AssetDubboService assetService;
    @DubboReference(timeout = 5000, retries = 0, check = false)
    private FundDubboService fundService;
    @DubboReference(timeout = 5000, retries = 0, check = false)
    private MerchantDubboService merchantService;

    public TradeOrderService(TradeOrderMapper orderMapper, PaymentOrderMapper paymentMapper,
                             PaymentCallbackMapper callbackMapper, DeliveryRecordMapper deliveryMapper,
                             DeliveryEvidenceMapper evidenceMapper, DisputeMapper disputeMapper,
                             DisputeMessageMapper disputeMessageMapper, ArbitrationMapper arbitrationMapper,
                            OrderSettlementMapper settlementMapper, OrderReviewMapper reviewMapper,
                            TradeStatusLogService statusLog, ObjectMapper objectMapper,
                            TransactionTemplate transactionTemplate, RiskClient riskClient,
                            OrderRiskStateWriter riskStateWriter) {
        this.orderMapper = orderMapper;
        this.paymentMapper = paymentMapper;
        this.callbackMapper = callbackMapper;
        this.deliveryMapper = deliveryMapper;
        this.evidenceMapper = evidenceMapper;
        this.disputeMapper = disputeMapper;
        this.disputeMessageMapper = disputeMessageMapper;
        this.arbitrationMapper = arbitrationMapper;
        this.settlementMapper = settlementMapper;
        this.reviewMapper = reviewMapper;
        this.statusLog = statusLog;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
        this.riskClient = riskClient;
        this.riskStateWriter = riskStateWriter;
    }

    @Transactional(rollbackFor = Exception.class)
    public TradeOrder create(Long buyerId, Long itemId, Integer quantity) {
        requireCreateParameters(buyerId, itemId, quantity);
        String orderNo = nextBusinessNo("TR", buyerId);
        AssetReservationResult reservation = assetService.reserve(itemId, quantity, orderNo, paymentExpireMinutes);
        if (!reservation.isSuccess()) {
            throw new IllegalArgumentException(reservation.getMessage());
        }
        try {
            ItemSnapshotDTO snapshot = reservation.getItemSnapshot();
            calculateFee(snapshot.getPrice().multiply(BigDecimal.valueOf(quantity)));
            if (buyerId.equals(snapshot.getSellerId())) {
                throw new IllegalArgumentException("买家和卖家不能是同一个用户");
            }
            String eventNo = RiskSupport.nextEventNo("ORDER");
            RiskEvaluateRequest riskRequest = RiskEvaluateRequest.builder()
                    .eventNo(eventNo)
                    .scene("ORDER")
                    .eventType("CREATE")
                    .bizType("ORDER")
                    .bizNo(orderNo)
                    .userId(buyerId)
                    .merchantId(snapshot.getMerchantId())
                    .itemId(itemId)
                    .orderNo(orderNo)
                    .amount(snapshot.getPrice().multiply(BigDecimal.valueOf(quantity)))
                    .ipHash(riskClient.ipHash())
                    .deviceHash(riskClient.deviceHash())
                    .payload(createOrderPayload(snapshot, quantity))
                    .build();
            RiskDecisionResult decision = riskClient.evaluate(riskRequest);
            if (RiskDecisionResult.ACTION_REJECT.equals(decision.getAction())) {
                throw new IllegalArgumentException(decision.getMessage());
            }
            LocalDateTime now = LocalDateTime.now();
            TradeOrder order = buildOrder(buyerId, orderNo, snapshot, quantity, now);
            applyDecisionToOrder(order, decision);
            orderMapper.insert(order);
            PaymentOrder payment = buildPayment(order, now);
            paymentMapper.insert(payment);

            TradeOrder stored = order;
            stored.setPaymentNo(payment.getPaymentNo());
            statusLog.log(orderNo, null, "WAIT_PAY", "ORDER", "BUYER", buyerId, "创建担保订单");
            riskClient.confirmAfterCommit(eventNo);
            return stored;
        } catch (RuntimeException e) {
            assetService.release(orderNo);
            throw e;
        }
    }

    public PaymentOrder getPayment(String orderNo) {
        TradeOrder order = requireOrder(orderNo);
        if (closeExpiredOrderIfPayExpired(order)) {
            throw new IllegalArgumentException("支付已超时，订单已关闭");
        }
        return requirePayment(orderNo);
    }

    public PaymentOrder getPaymentByNo(String paymentNo) {
        return requirePaymentByNo(paymentNo);
    }

    public PaymentOrder startPay(String paymentNo) {
        PaymentOrder payment = requirePaymentByNo(paymentNo);
        TradeOrder order = requireOrder(payment.getOrderNo());
        if (closeExpiredOrderIfPayExpired(order)) {
            throw new IllegalArgumentException("支付已超时，订单已关闭");
        }
        requirePaymentAllowed(order);
        if (!"INIT".equals(payment.getStatus())) {
            return payment;
        }
        paymentMapper.startPaying(order.getOrderNo(), LocalDateTime.now());
        return requirePaymentByNo(paymentNo);
    }

    @Transactional(rollbackFor = Exception.class)
    public String handleCallback(String callbackNo, String paymentNo, String orderNo, BigDecimal amount,
                                 String result, String signature, String rawPayload) {
        PaymentOrder payment = requirePaymentByNo(paymentNo);
        if (!payment.getOrderNo().equals(orderNo)) {
            return "ORDER_NO_MISMATCH";
        }
        if (!verifyCallback(payment, amount, signature)) {
            return "SIGNATURE_INVALID";
        }
        boolean newCallback = saveCallback(callbackNo, paymentNo, result, amount, signature, rawPayload);
        if (!newCallback) {
            return "CALLBACK_DUPLICATED";
        }
        if (!"SUCCESS".equals(result)) {
            paymentMapper.markFailed(paymentNo, LocalDateTime.now());
            return "CALLBACK_SAVED";
        }

        TradeOrder order = requireOrder(orderNo);
        requirePaymentAllowed(order);
        LocalDateTime now = LocalDateTime.now();
        if (order.getOrderAmount() == null || amount.compareTo(order.getOrderAmount()) != 0) {
            return "AMOUNT_MISMATCH";
        }
        if ("SUCCESS".equals(payment.getStatus())) {
            return "CALLBACK_DUPLICATED";
        }
        if (orderMapper.markPaid(orderNo, now, deliveryTimeoutMinutes) <= 0) {
            TradeOrder currentOrder = orderMapper.selectByOrderNoForUpdate(orderNo);
            if (currentOrder == null) {
                throw new IllegalStateException("订单不存在，支付回调处理失败");
            }
            if ("SUCCESS".equals(currentOrder.getPayStatus())) {
                return "CALLBACK_DUPLICATED";
            }
            boolean timeoutClosed = closeExpiredOrder(orderNo, "SYSTEM", null, "支付超时被动关单") > 0;
            paymentMapper.markLateSuccess(paymentNo, now);
            if (timeoutClosed) {
                statusLog.log(orderNo, "WAIT_PAY", "CANCELLED", "ORDER", "SYSTEM", null,
                        "支付超时后到账，订单保持关闭");
            }
            return "PAY_EXPIRED_LATE_SUCCESS";
        }
        paymentMapper.markSuccess(paymentNo, now);

        FundOperationResult freeze = fundService.freezeEscrow(orderNo, order.getSellerId(),
                order.getMerchantId(), order.getOrderAmount());
        if (!freeze.isSuccess()) {
            statusLog.log(orderNo, "FREEZE_PENDING", "FREEZE_PENDING", "ESCROW", "SYSTEM", null,
                    "资金冻结失败: " + freeze.getMessage());
            throw new IllegalStateException(freeze.getMessage());
        }
        if (!assetService.confirm(orderNo)) {
            statusLog.log(orderNo, "FREEZE_PENDING", "FREEZE_PENDING", "ASSET", "SYSTEM", null, "资产确认失败");
            throw new IllegalStateException("资产确认失败");
        }
        if (orderMapper.markEscrowFrozen(orderNo, now) <= 0) {
            statusLog.log(orderNo, "FREEZE_PENDING", "FREEZE_PENDING", "ESCROW", "SYSTEM", null,
                    "担保资金状态确认失败");
            throw new IllegalStateException("担保资金状态确认失败");
        }
        statusLog.log(orderNo, "WAIT_PAY", "PAID", "ORDER", "SYSTEM", null, "Mock 支付成功");
        statusLog.log(orderNo, "INIT", "SUCCESS", "PAY", "SYSTEM", null, "支付成功");
        statusLog.log(orderNo, "NONE", "FROZEN", "ESCROW", "SYSTEM", null, "资金冻结并确认卡密");
        return "SUCCESS";
    }

    @Transactional(rollbackFor = Exception.class)
    public int closeExpiredOrder(String orderNo, String operatorType, Long operatorId, String reason) {
        LocalDateTime now = LocalDateTime.now();
        if (orderMapper.markCancellingFromWaitPay(orderNo, now) <= 0) {
            return 0;
        }
        paymentMapper.markTimeout(orderNo, now);
        if (!assetService.release(orderNo)) {
            throw new IllegalStateException("释放资产预留失败");
        }
        orderMapper.markCancelled(orderNo, now);
        statusLog.log(orderNo, "WAIT_PAY", "CANCELLED", "ORDER", operatorType, operatorId, reason);
        return 1;
    }

    @Transactional(rollbackFor = Exception.class)
    public int cancelUnpaidOrder(Long buyerId, String orderNo, String reason) {
        TradeOrder order = requireOrder(orderNo);
        if (!order.getBuyerId().equals(buyerId)) {
            throw new IllegalArgumentException("只有买家可以取消订单");
        }
        LocalDateTime now = LocalDateTime.now();
        if (orderMapper.markCancellingByBuyer(orderNo, now) <= 0) {
            return 0;
        }
        paymentMapper.closeByOrder(orderNo, now);
        if (!assetService.release(orderNo)) {
            throw new IllegalStateException("释放资产预留失败");
        }
        orderMapper.markCancelled(orderNo, now);
        statusLog.log(orderNo, "WAIT_PAY", "CANCELLED", "ORDER", "BUYER", buyerId, reason);
        return 1;
    }

    @Transactional(rollbackFor = Exception.class)
    public TradeOrder deliver(Long sellerId, String orderNo, String content) {
        TradeOrder order = requireOrder(orderNo);
        if (!order.getSellerId().equals(sellerId)) {
            throw new IllegalArgumentException("只有卖家可以交付");
        }
        if (!"FROZEN".equals(order.getEscrowStatus())) {
            throw new IllegalStateException("资金未冻结，不能交付");
        }
        requireProgressAllowed(order);
        if (hasActiveDispute(order.getDisputeStatus())) {
            throw new IllegalStateException("售后处理中，不能交付");
        }
        ItemSnapshotDTO snapshot = readSnapshot(order);
        boolean autoCard = "AUTO_CARD".equals(snapshot.getDeliveryMode());
        boolean manualDelivery = "MANUAL_DELIVERY".equals(snapshot.getDeliveryMode());
        if (!autoCard && !manualDelivery) {
            throw new IllegalStateException("商品交付方式不支持");
        }
        String deliveryContent = content == null ? "" : content.trim();
        if (manualDelivery && deliveryContent.isEmpty()) {
            throw new IllegalArgumentException("手动交付必须填写交付说明");
        }
        LocalDateTime now = LocalDateTime.now();
        if (orderMapper.markDelivered(orderNo, now, autoConfirmHours) <= 0) {
            throw new IllegalStateException("当前状态不能交付");
        }
        recordOrderEvent(order, "DELIVERY", "DELIVER", order.getRiskStatus());
        DeliveryRecord record = new DeliveryRecord();
        record.setOrderNo(orderNo);
        record.setDeliveryType(autoCard ? "AUTO_CARD" : "MANUAL_DELIVERY");
        record.setDeliveryContent(autoCard && deliveryContent.isEmpty()
                ? "卡密已售出，请查看后确认" : deliveryContent);
        record.setCardSecretIds("[]");
        record.setStatus("DELIVERED");
        record.setDeliveredTime(now);
        deliveryMapper.insert(record);
        statusLog.log(orderNo, "PAID", "DELIVERED", "ORDER", "SELLER", sellerId, "卖家交付");
        return requireOrder(orderNo);
    }

    public List<CardSecretDTO> viewSecrets(Long buyerId, String orderNo) {
        TradeOrder order = requireOrder(orderNo);
        if (!order.getBuyerId().equals(buyerId)) {
            throw new IllegalArgumentException("只有买家可以查看卡密");
        }
        if (!"SUCCESS".equals(order.getPayStatus())
                || !SECRET_VIEWABLE_ORDER_STATUSES.contains(order.getOrderStatus())) {
            throw new IllegalStateException("订单尚未交付，不能查看卡密");
        }
        if (!SECRET_VIEWABLE_ESCROW_STATUSES.contains(order.getEscrowStatus())) {
            throw new IllegalStateException("订单资金状态异常，不能查看卡密");
        }
        if (hasActiveDispute(order.getDisputeStatus())) {
            throw new IllegalStateException("售后处理中，不能查看卡密");
        }
        List<CardSecretDTO> cards = assetService.getSoldCardSecrets(orderNo);
        if (cards.isEmpty()) {
            throw new IllegalStateException("订单卡密不存在");
        }
        markSecretViewed(orderNo);
        statusLog.log(orderNo, order.getDeliveryStatus(), "VIEWED", "SECRET", "BUYER", buyerId, "买家查看卡密明文");
        return cards;
    }

    @Transactional(rollbackFor = Exception.class)
    public DeliveryRecord viewDelivery(Long buyerId, String orderNo) {
        TradeOrder order = requireOrder(orderNo);
        if (!order.getBuyerId().equals(buyerId)) {
            throw new IllegalArgumentException("只有买家可以查看交付信息");
        }
        if (!"SUCCESS".equals(order.getPayStatus())
                || !SECRET_VIEWABLE_ORDER_STATUSES.contains(order.getOrderStatus())) {
            throw new IllegalStateException("订单尚未交付，不能查看交付信息");
        }
        if (!SECRET_VIEWABLE_ESCROW_STATUSES.contains(order.getEscrowStatus())) {
            throw new IllegalStateException("订单资金状态异常，不能查看交付信息");
        }
        if (hasActiveDispute(order.getDisputeStatus())) {
            throw new IllegalStateException("售后处理中，不能查看交付信息");
        }
        DeliveryRecord record = deliveryMapper.selectOne(new LambdaQueryWrapper<DeliveryRecord>()
                .eq(DeliveryRecord::getOrderNo, orderNo));
        if (record == null) {
            throw new IllegalStateException("交付记录不存在");
        }
        if (record.getFirstViewTime() == null) {
            LocalDateTime now = LocalDateTime.now();
            orderMapper.markDeliveryViewed(orderNo, now);
            deliveryMapper.markViewed(orderNo, now);
            record.setFirstViewTime(now);
            record.setStatus("VIEWED");
            statusLog.log(orderNo, "DELIVERED", "VIEWED", "DELIVERY", "BUYER", buyerId,
                    "买家查看交付信息");
        }
        return record;
    }

    @Transactional(rollbackFor = Exception.class)
    public TradeOrder confirm(Long operatorId, String orderNo, String operatorType) {
        TradeOrder order = requireOrder(orderNo);
        if ("BUYER".equals(operatorType) && !order.getBuyerId().equals(operatorId)) {
            throw new IllegalArgumentException("只有买家可以确认收货");
        }
        if (hasActiveDispute(order.getDisputeStatus())) {
            throw new IllegalStateException("售后处理中，不能确认收货");
        }
        requireProgressAllowed(order);
        LocalDateTime now = LocalDateTime.now();
        int updated = "SYSTEM".equals(operatorType)
                ? orderMapper.markConfirmedForAutoConfirm(orderNo, now, settleCooldownHours)
                : orderMapper.markConfirmedByBuyer(orderNo, now, settleCooldownHours);
        if (updated <= 0) {
            throw new IllegalStateException("当前状态不能确认");
        }
        deliveryMapper.confirmDelivery(orderNo, now);
        if ("LIMITED".equals(order.getRiskStatus())) {
            orderMapper.delaySettlement(orderNo, now.plusHours(settleCooldownHours), now);
        }
        recordOrderEvent(order, "CONFIRM", "CONFIRM", order.getRiskStatus());
        statusLog.log(orderNo, "DELIVERED", "CONFIRMED", "ORDER", operatorType, operatorId, "确认收货");
        return requireOrder(orderNo);
    }

    @Transactional(rollbackFor = Exception.class)
    public TradeOrder settle(String orderNo) {
        LocalDateTime now = LocalDateTime.now();
        TradeOrder order = requireOrder(orderNo);
        requireProgressAllowed(order);
        if (!"CONFIRMED".equals(order.getOrderStatus()) && !"SETTLING".equals(order.getOrderStatus())) {
            return order;
        }
        if ("CONFIRMED".equals(order.getOrderStatus()) && orderMapper.markSettling(orderNo, now) <= 0) {
            return requireOrder(orderNo);
        }
        order = requireOrder(orderNo);
        OrderSettlement existing = settlementMapper.selectOne(new LambdaQueryWrapper<OrderSettlement>()
                .eq(OrderSettlement::getOrderNo, orderNo));
        if (existing != null && !"SUCCESS".equals(existing.getStatus())) {
            throw new IllegalStateException("订单结算单状态异常");
        }
        if (existing != null && existing.getFundTransactionNo() == null) {
            throw new IllegalStateException("订单结算单缺少资金流水，禁止放款");
        }

        if (existing == null) {
            BigDecimal fee = calculateFee(order.getOrderAmount());
            BigDecimal income = order.getOrderAmount().subtract(fee);
            FundOperationResult settle = fundService.settle(orderNo, order.getSellerId(), order.getMerchantId(),
                    order.getOrderAmount(), fee, income);
            if (!settle.isSuccess()) {
                throw new IllegalStateException(settle.getMessage());
            }
            existing = buildSettlement(orderNo, order, fee, income, settle.getTransactionNo(), now);
            settlementMapper.insert(existing);
            orderMapper.updateFeeAndIncome(order.getId(), fee, income);
        }

        if (existing.getFundTransactionNo() != null) {
            FundOperationResult available = fundService.settlePendingToAvailable(orderNo,
                    order.getSellerId(), existing.getSellerIncome());
            if (!available.isSuccess()) {
                throw new IllegalStateException("待结算余额转可用余额失败");
            }
        }

        if (orderMapper.markSettled(orderNo, now) <= 0) {
            throw new IllegalStateException("订单已被其他任务处理，结算中断");
        }
        statusLog.log(orderNo, "CONFIRMED", "SETTLED", "ORDER", "SYSTEM", null, "结算冷却期结束并放款");
        return requireOrder(orderNo);
    }

    @Transactional(rollbackFor = Exception.class)
    public Dispute openDispute(Long userId, String orderNo, String type, String reason, BigDecimal refundAmount) {
        TradeOrder order = requireOrder(orderNo);
        if (!order.getBuyerId().equals(userId) && !order.getSellerId().equals(userId)) {
            throw new IllegalArgumentException("无权发起该订单售后");
        }
        if (!"PAID".equals(order.getOrderStatus()) && !"DELIVERED".equals(order.getOrderStatus())) {
            throw new IllegalArgumentException("当前订单状态不能发起售后");
        }
        if (hasActiveDispute(order.getDisputeStatus())) {
            throw new IllegalArgumentException("该订单已有处理中的售后");
        }
        List<SensitiveWordHitDTO> sensitiveHits =
                SensitiveWordScanner.scan(reason, riskClient.sensitiveWords());
        requireSafeDisputeContent(sensitiveHits);
        String eventNo = RiskSupport.nextEventNo("DISPUTE");
        RiskEvaluateRequest riskRequest = RiskEvaluateRequest.builder()
                .eventNo(eventNo)
                .scene("DISPUTE")
                .eventType("OPEN")
                .bizType("ORDER")
                .bizNo(orderNo)
                .userId(userId)
                .merchantId(order.getMerchantId())
                .orderNo(orderNo)
                .amount(refundAmount == null ? order.getOrderAmount() : refundAmount)
                .ipHash(riskClient.ipHash())
                .deviceHash(riskClient.deviceHash())
                .payload(disputePayload(order))
                .sensitiveHits(sensitiveHits)
                .build();
        RiskDecisionResult decision = riskClient.evaluate(riskRequest);
        if (RiskDecisionResult.ACTION_REJECT.equals(decision.getAction())) {
            throw new IllegalArgumentException(decision.getMessage());
        }
        String riskStatus = RiskSupport.toRiskStatus(decision.getAction());
        if (!"NORMAL".equals(riskStatus)) {
            riskStateWriter.updateRiskStatus(orderNo, riskStatus, decision.getRiskLevel(),
                    decision.getDecisionNo(), decision.getMessage());
        }
        if (RiskDecisionResult.ACTION_FREEZE.equals(decision.getAction())
                || RiskDecisionResult.ACTION_MANUAL_REVIEW.equals(decision.getAction())) {
            throw new IllegalStateException(decision.getMessage());
        }
        if (orderMapper.markDispute(orderNo, "ARBITRATING", LocalDateTime.now()) <= 0) {
            throw new IllegalArgumentException("订单售后状态已变化，请刷新后重试");
        }
        Dispute dispute = new Dispute();
        dispute.setDisputeNo(nextBusinessNo("DP", userId));
        dispute.setOrderNo(orderNo);
        dispute.setBuyerId(order.getBuyerId());
        dispute.setSellerId(order.getSellerId());
        dispute.setDisputeType(type);
        dispute.setReason(reason);
        dispute.setProposedRefundAmount(refundAmount == null ? order.getOrderAmount() : refundAmount);
        dispute.setStatus("ARBITRATING");
        dispute.setDeadlineTime(LocalDateTime.now().plusHours(24));
        dispute.setCreatedTime(LocalDateTime.now());
        dispute.setUpdatedTime(LocalDateTime.now());
        disputeMapper.insert(dispute);
        transactionTemplate.executeWithoutResult(status -> merchantService.disputeOrder(order.getMerchantId()));
        riskClient.confirmAfterCommit(eventNo);
        statusLog.log(orderNo, "NONE", "ARBITRATING", "DISPUTE", "USER", userId, "发起售后");
        return dispute;
    }

    public List<DisputeMessage> addDisputeMessage(Long userId, String disputeNo, String message) {
        Dispute dispute = requireDispute(disputeNo);
        if (!dispute.getBuyerId().equals(userId) && !dispute.getSellerId().equals(userId)) {
            throw new IllegalArgumentException("无权回复该售后");
        }
        DisputeMessage record = new DisputeMessage();
        record.setDisputeNo(disputeNo);
        record.setSenderType(dispute.getBuyerId().equals(userId) ? "BUYER" : "SELLER");
        record.setSenderId(userId);
        record.setMessage(message);
        record.setCreatedTime(LocalDateTime.now());
        disputeMessageMapper.insert(record);
        return listDisputeMessages(disputeNo);
    }

    public DeliveryEvidence addEvidence(Long userId, String orderNo, String evidenceType,
                                        String fileUrl, String content) {
        TradeOrder order = requireOrder(orderNo);
        if (!order.getBuyerId().equals(userId) && !order.getSellerId().equals(userId)) {
            throw new IllegalArgumentException("无权提交该订单证据");
        }
        DeliveryEvidence evidence = new DeliveryEvidence();
        evidence.setOrderNo(orderNo);
        evidence.setEvidenceType(evidenceType);
        evidence.setFileUrl(fileUrl);
        evidence.setContent(content);
        evidence.setUploaderType(order.getBuyerId().equals(userId) ? "BUYER" : "SELLER");
        evidence.setUploaderId(userId);
        evidence.setCreatedTime(LocalDateTime.now());
        evidenceMapper.insert(evidence);
        return evidence;
    }

    @Transactional(rollbackFor = Exception.class)
    public Arbitration arbitrate(Long adminId, String disputeNo, String result, String reason) {
        Dispute dispute = requireDispute(disputeNo);
        if (!"ARBITRATING".equals(dispute.getStatus())) {
            throw new IllegalArgumentException("售后单不存在或已处理");
        }
        TradeOrder order = requireOrder(dispute.getOrderNo());
        if ("REFUND_ALL".equals(result)) {
            FundOperationResult refund = fundService.refundEscrow(order.getOrderNo(), order.getBuyerId(),
                    order.getOrderAmount());
            if (!refund.isSuccess()) {
                throw new IllegalStateException(refund.getMessage());
            }
            if (!assetService.invalidateByOrderNo(order.getOrderNo())) {
                throw new IllegalStateException("资产退款处理失败");
            }
            if (orderMapper.markRefunded(order.getOrderNo(), LocalDateTime.now()) <= 0) {
                throw new IllegalStateException("订单状态变化，仲裁退款失败");
            }
            merchantService.refundOrder(order.getMerchantId());
        } else if ("RELEASE_ALL".equals(result)) {
            if (!"DELIVERED".equals(order.getOrderStatus())) {
                throw new IllegalArgumentException("仅已交付订单可以仲裁全额放款");
            }
            if (orderMapper.markSettlingByArbitration(order.getOrderNo(), LocalDateTime.now()) <= 0) {
                throw new IllegalStateException("订单状态变化，仲裁放款失败");
            }
            settle(order.getOrderNo());
        } else {
            throw new IllegalArgumentException("阶段一仅支持全额退款或全额放款");
        }

        Arbitration arbitration = buildArbitration(dispute, result, reason, adminId, order);
        arbitrationMapper.insert(arbitration);
        dispute.setStatus("RESOLVED");
        dispute.setUpdatedTime(LocalDateTime.now());
        disputeMapper.updateById(dispute);
        orderMapper.markDispute(order.getOrderNo(), "RESOLVED", LocalDateTime.now());
        return arbitration;
    }

    @Transactional(rollbackFor = Exception.class)
    public OrderReview review(Long buyerId, String orderNo, Integer score, String content) {
        TradeOrder order = requireOrder(orderNo);
        if (!order.getBuyerId().equals(buyerId)) {
            throw new IllegalArgumentException("只有买家可以评价");
        }
        if (!"SETTLED".equals(order.getOrderStatus())) {
            throw new IllegalStateException("订单结算后才能评价");
        }
        OrderReview review = new OrderReview();
        review.setOrderNo(orderNo);
        review.setBuyerId(buyerId);
        review.setMerchantId(order.getMerchantId());
        review.setScore(score);
        review.setContent(content);
        review.setCreatedTime(LocalDateTime.now());
        reviewMapper.insert(review);
        transactionTemplate.executeWithoutResult(status -> merchantService.completeOrder(
                order.getMerchantId(), BigDecimal.valueOf(score)));
        return review;
    }

    public List<TradeOrder> listByUser(Long userId) {
        return orderMapper.selectList(new LambdaQueryWrapper<TradeOrder>()
                .and(wrapper -> wrapper.eq(TradeOrder::getBuyerId, userId).or().eq(TradeOrder::getSellerId, userId))
                .orderByDesc(TradeOrder::getId)
                .last("LIMIT 100"));
    }

    public TradeOrder getVisibleOrder(Long userId, String orderNo) {
        TradeOrder order = requireOrder(orderNo);
        if (!order.getBuyerId().equals(userId) && !order.getSellerId().equals(userId)) {
            throw new IllegalArgumentException("无权查看该订单");
        }
        return order;
    }

    public List<TradeOrderStatusLog> getLogs(String orderNo) {
        return statusLog.listByOrderNo(orderNo);
    }

    public List<DeliveryEvidence> getEvidence(Long userId, String orderNo) {
        getVisibleOrder(userId, orderNo);
        return getEvidenceByOrderNo(orderNo);
    }

    public List<Dispute> listPendingDisputes() {
        return disputeMapper.selectList(new LambdaQueryWrapper<Dispute>()
                .eq(Dispute::getStatus, "ARBITRATING")
                .orderByAsc(Dispute::getDeadlineTime)
                .last("LIMIT 100"));
    }

    public List<DeliveryEvidence> getEvidenceForAdmin(String orderNo) {
        requireOrder(orderNo);
        return getEvidenceByOrderNo(orderNo);
    }

    public List<DisputeMessage> listDisputeMessages(String disputeNo) {
        return disputeMessageMapper.selectList(new LambdaQueryWrapper<DisputeMessage>()
                .eq(DisputeMessage::getDisputeNo, disputeNo)
                .orderByAsc(DisputeMessage::getId));
    }

    public Dispute getDispute(Long userId, String disputeNo) {
        Dispute dispute = requireDispute(disputeNo);
        if (!dispute.getBuyerId().equals(userId) && !dispute.getSellerId().equals(userId)) {
            throw new IllegalArgumentException("无权查看该售后");
        }
        return dispute;
    }

    public Dispute getDisputeForAdmin(String disputeNo) {
        return requireDispute(disputeNo);
    }

    private List<DeliveryEvidence> getEvidenceByOrderNo(String orderNo) {
        return evidenceMapper.selectList(new LambdaQueryWrapper<DeliveryEvidence>()
                .eq(DeliveryEvidence::getOrderNo, orderNo)
                .orderByAsc(DeliveryEvidence::getId));
    }

    public int closeExpiredPayments(int limit) {
        return processOrders(orderMapper.selectExpiredPayOrders(LocalDateTime.now(), limit),
                order -> closeExpiredOrder(order.getOrderNo(), "SYSTEM", null, "支付超时主动关单"));
    }

    public int autoConfirm(int limit) {
        return processOrders(orderMapper.selectAutoConfirmOrders(LocalDateTime.now(), limit),
                order -> confirm(null, order.getOrderNo(), "SYSTEM"));
    }

    public int settleDueOrders(int limit) {
        return processOrders(orderMapper.selectSettleableOrders(LocalDateTime.now(), limit),
                order -> settle(order.getOrderNo()));
    }

    public int handleDeliveryTimeout(int limit) {
        List<TradeOrder> orders = orderMapper.selectDeliveryTimeoutOrders(LocalDateTime.now(), limit);
        int processed = 0;
        for (TradeOrder order : orders) {
            try {
                transactionTemplate.executeWithoutResult(status -> refundDeliveryTimeoutOrder(order));
                processed++;
            } catch (RuntimeException e) {
                log.warn("交付超时退款失败，orderNo={}", order.getOrderNo(), e);
            }
        }
        return processed;
    }

    private void refundDeliveryTimeoutOrder(TradeOrder order) {
        FundOperationResult refund = fundService.refundEscrow(order.getOrderNo(), order.getBuyerId(),
                order.getOrderAmount());
        if (!refund.isSuccess()) {
            throw new IllegalStateException(refund.getMessage());
        }
        if (!assetService.invalidateByOrderNo(order.getOrderNo())) {
            throw new IllegalStateException("资产退款处理失败");
        }
        if (orderMapper.markRefunded(order.getOrderNo(), LocalDateTime.now()) <= 0) {
            throw new IllegalStateException("订单已被其他任务处理，交付超时退款中断");
        }
        merchantService.refundOrder(order.getMerchantId());
        statusLog.log(order.getOrderNo(), "PAID", "REFUNDED", "ORDER", "SYSTEM", null,
                "卖家超过交付截止时间，系统自动全额退款");
    }

    private int processOrders(List<TradeOrder> orders, Consumer<TradeOrder> action) {
        int processed = 0;
        for (TradeOrder order : orders) {
            try {
                transactionTemplate.executeWithoutResult(status -> action.accept(order));
                processed++;
            } catch (RuntimeException e) {
                log.warn("担保交易任务处理失败，orderNo={}", order.getOrderNo(), e);
            }
        }
        return processed;
    }

    private TradeOrder buildOrder(Long buyerId, String orderNo, ItemSnapshotDTO snapshot,
                                  Integer quantity, LocalDateTime now) {
        TradeOrder order = new TradeOrder();
        order.setOrderNo(orderNo);
        order.setBuyerId(buyerId);
        order.setSellerId(snapshot.getSellerId());
        order.setMerchantId(snapshot.getMerchantId());
        order.setItemId(snapshot.getItemId());
        order.setItemSnapshot(writeJson(snapshot));
        order.setQuantity(quantity);
        order.setOrderAmount(snapshot.getPrice().multiply(BigDecimal.valueOf(quantity)));
        order.setFeeAmount(BigDecimal.ZERO);
        order.setSellerIncome(BigDecimal.ZERO);
        order.setOrderStatus("WAIT_PAY");
        order.setPayStatus("INIT");
        order.setDeliveryStatus("WAIT_DELIVERY");
        order.setEscrowStatus("NONE");
        order.setDisputeStatus("NONE");
        order.setRiskStatus("NORMAL");
        order.setRiskLevel("LOW");
        order.setPayDeadline(now.plusMinutes(paymentExpireMinutes));
        order.setVersion(0);
        order.setCreatedTime(now);
        order.setUpdatedTime(now);
        return order;
    }

    private void applyDecisionToOrder(TradeOrder order, RiskDecisionResult decision) {
        String riskStatus = RiskSupport.toRiskStatus(decision.getAction());
        order.setRiskStatus(riskStatus);
        order.setRiskLevel(decision.getRiskLevel() == null ? "LOW" : decision.getRiskLevel());
        order.setRiskDecisionNo(decision.getDecisionNo());
        order.setRiskReason(decision.getMessage());
    }

    private void recordOrderEvent(TradeOrder order, String scene, String eventType, String orderRiskStatus) {
        riskClient.recordEvent(RiskEvaluateRequest.builder()
                .eventNo(RiskSupport.nextEventNo(scene))
                .scene(scene)
                .eventType(eventType)
                .bizType("ORDER")
                .bizNo(order.getOrderNo())
                .userId(order.getBuyerId())
                .merchantId(order.getMerchantId())
                .itemId(order.getItemId())
                .orderNo(order.getOrderNo())
                .amount(order.getOrderAmount())
                .ipHash(riskClient.ipHash())
                .deviceHash(riskClient.deviceHash())
                .payload(orderEventPayload(order, orderRiskStatus))
                .build());
    }

    private Map<String, Object> orderEventPayload(TradeOrder order, String orderRiskStatus) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("orderId", order.getId());
        payload.put("sellerId", order.getSellerId());
        payload.put("quantity", order.getQuantity());
        payload.put("deliveryMode", deliveryModeOf(order));
        payload.put("orderRiskStatus", nullToNormal(orderRiskStatus));
        if (order.getDeliveredTime() != null && order.getConfirmedTime() != null) {
            payload.put("payToDeliverSeconds", java.time.Duration.between(
                    order.getDeliveredTime(), order.getConfirmedTime()).toSeconds());
        }
        return payload;
    }

    private Map<String, Object> createOrderPayload(ItemSnapshotDTO snapshot, Integer quantity) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("sellerId", snapshot.getSellerId());
        payload.put("quantity", quantity);
        payload.put("deliveryMode", snapshot.getDeliveryMode());
        payload.put("orderRiskStatus", "NORMAL");
        return payload;
    }

    private String deliveryModeOf(TradeOrder order) {
        try {
            ItemSnapshotDTO snapshot = readSnapshot(order);
            return String.valueOf(snapshot.getDeliveryMode());
        } catch (RuntimeException e) {
            return "UNKNOWN";
        }
    }

    private Map<String, Object> disputePayload(TradeOrder order) {
        Map<String, Object> payload = orderEventPayload(order, order.getRiskStatus());
        return payload;
    }

    private void requirePaymentAllowed(TradeOrder order) {
        if (RISK_BLOCKED_STATUSES.contains(nullToNormal(order.getRiskStatus()))) {
            throw new IllegalStateException("订单安全审核中或已冻结，禁止支付");
        }
    }

    private void requireProgressAllowed(TradeOrder order) {
        if (RISK_BLOCKED_STATUSES.contains(nullToNormal(order.getRiskStatus()))) {
            throw new IllegalStateException("订单安全审核中或已冻结，禁止推进");
        }
    }

    private void requireSafeDisputeContent(List<SensitiveWordHitDTO> hits) {
        boolean unsafe = hits.stream().map(SensitiveWordHitDTO::getCategory).anyMatch(category ->
                "ILLEGAL_ASSET".equals(category) || "OFF_PLATFORM_CONTACT".equals(category)
                        || "PRIVATE_TRANSACTION".equals(category));
        if (unsafe) {
            throw new IllegalArgumentException("售后说明包含不安全内容");
        }
    }

    private String nullToNormal(String value) {
        return value == null || value.isBlank() ? "NORMAL" : value;
    }

    private PaymentOrder buildPayment(TradeOrder order, LocalDateTime now) {
        PaymentOrder payment = new PaymentOrder();
        payment.setPaymentNo("PAY" + order.getOrderNo());
        payment.setOrderNo(order.getOrderNo());
        payment.setChannel("MOCK_WECHAT");
        payment.setAmount(order.getOrderAmount());
        payment.setStatus("INIT");
        payment.setCallbackTokenHash(sha256(order.getOrderNo() + order.getOrderAmount()));
        payment.setExpireTime(order.getPayDeadline());
        payment.setCreatedTime(now);
        payment.setUpdatedTime(now);
        return payment;
    }

    private OrderSettlement buildSettlement(String orderNo, TradeOrder order, BigDecimal fee,
                                            BigDecimal income, String transactionNo, LocalDateTime now) {
        OrderSettlement settlement = new OrderSettlement();
        settlement.setOrderNo(orderNo);
        settlement.setOrderAmount(order.getOrderAmount());
        settlement.setFeeAmount(fee);
        settlement.setSellerIncome(income);
        settlement.setStatus("SUCCESS");
        settlement.setFundTransactionNo(transactionNo);
        settlement.setSettledTime(now);
        settlement.setCreatedTime(now);
        settlement.setUpdatedTime(now);
        return settlement;
    }

    private Arbitration buildArbitration(Dispute dispute, String result, String reason,
                                         Long adminId, TradeOrder order) {
        Arbitration arbitration = new Arbitration();
        arbitration.setDisputeNo(dispute.getDisputeNo());
        arbitration.setOrderNo(dispute.getOrderNo());
        arbitration.setResult(result);
        arbitration.setRefundAmount("REFUND_ALL".equals(result) ? order.getOrderAmount() : BigDecimal.ZERO);
        arbitration.setReleaseAmount("RELEASE_ALL".equals(result) ? order.getOrderAmount() : BigDecimal.ZERO);
        arbitration.setDepositDeductAmount(BigDecimal.ZERO);
        arbitration.setReason(reason);
        arbitration.setArbitratorId(adminId);
        arbitration.setAppealStatus("NONE");
        arbitration.setCreatedTime(LocalDateTime.now());
        return arbitration;
    }

    private void markSecretViewed(String orderNo) {
        LocalDateTime now = LocalDateTime.now();
        orderMapper.markDeliveryViewed(orderNo, now);
        deliveryMapper.markViewed(orderNo, now);
    }

    private BigDecimal calculateFee(BigDecimal orderAmount) {
        if (orderAmount == null || orderAmount.compareTo(minFee) <= 0) {
            throw new IllegalArgumentException("订单金额必须大于最低手续费");
        }
        BigDecimal fee = orderAmount.multiply(feeRatePercent).divide(HUNDRED, 2, RoundingMode.HALF_UP);
        return fee.max(minFee).min(orderAmount);
    }

    private boolean hasActiveDispute(String disputeStatus) {
        return "OPEN".equals(disputeStatus) || "NEGOTIATING".equals(disputeStatus)
                || "ARBITRATING".equals(disputeStatus) || "APPEALED".equals(disputeStatus);
    }

    private TradeOrder requireOrder(String orderNo) {
        TradeOrder order = orderMapper.selectOne(new LambdaQueryWrapper<TradeOrder>()
                .eq(TradeOrder::getOrderNo, orderNo));
        if (order == null) {
            throw new IllegalArgumentException("订单不存在");
        }
        return order;
    }

    private PaymentOrder requirePayment(String orderNo) {
        PaymentOrder payment = paymentMapper.selectOne(new LambdaQueryWrapper<PaymentOrder>()
                .eq(PaymentOrder::getOrderNo, orderNo));
        if (payment == null) {
            throw new IllegalArgumentException("支付单不存在");
        }
        return payment;
    }

    private PaymentOrder requirePaymentByNo(String paymentNo) {
        PaymentOrder payment = paymentMapper.selectOne(new LambdaQueryWrapper<PaymentOrder>()
                .eq(PaymentOrder::getPaymentNo, paymentNo));
        if (payment == null) {
            throw new IllegalArgumentException("支付单不存在");
        }
        return payment;
    }

    private Dispute requireDispute(String disputeNo) {
        Dispute dispute = disputeMapper.selectOne(new LambdaQueryWrapper<Dispute>()
                .eq(Dispute::getDisputeNo, disputeNo));
        if (dispute == null) {
            throw new IllegalArgumentException("售后单不存在");
        }
        return dispute;
    }

    private boolean isPayExpired(TradeOrder order) {
        return order.getPayDeadline() != null && order.getPayDeadline().isBefore(LocalDateTime.now());
    }

    private boolean closeExpiredOrderIfPayExpired(TradeOrder order) {
        if (!isPayExpired(order)) {
            return false;
        }
        Integer closed = transactionTemplate.execute(status ->
                closeExpiredOrder(order.getOrderNo(), "SYSTEM", null, "支付超时被动关单"));
        return closed != null && closed > 0;
    }

    private boolean verifyCallback(PaymentOrder payment, BigDecimal amount, String signature) {
        if (amount == null || amount.compareTo(payment.getAmount()) != 0) {
            return false;
        }
        String expected = sha256(payment.getPaymentNo() + "|" + amount + "|" + payment.getCallbackTokenHash());
        return expected.equals(signature);
    }

    private boolean saveCallback(String callbackNo, String paymentNo, String result, BigDecimal amount,
                                 String signature, String rawPayload) {
        PaymentCallback callback = new PaymentCallback();
        callback.setCallbackNo(callbackNo);
        callback.setPaymentNo(paymentNo);
        callback.setResult(result);
        callback.setAmount(amount);
        callback.setSignature(signature);
        callback.setRawPayload(rawPayload);
        callback.setReceivedTime(LocalDateTime.now());
        try {
            callbackMapper.insert(callback);
            return true;
        } catch (org.springframework.dao.DuplicateKeyException ignored) {
            return false;
        }
    }

    private String writeJson(ItemSnapshotDTO snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (Exception e) {
            throw new IllegalStateException("商品快照序列化失败", e);
        }
    }

    private ItemSnapshotDTO readSnapshot(TradeOrder order) {
        try {
            ItemSnapshotDTO snapshot = objectMapper.readValue(order.getItemSnapshot(), ItemSnapshotDTO.class);
            if (snapshot == null || snapshot.getDeliveryMode() == null) {
                throw new IllegalStateException("商品快照缺少交付方式");
            }
            return snapshot;
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("商品快照反序列化失败", e);
        }
    }

    private String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 计算失败", e);
        }
    }

    private void requireCreateParameters(Long buyerId, Long itemId, Integer quantity) {
        if (buyerId == null || itemId == null || quantity == null || quantity <= 0) {
            throw new IllegalArgumentException("下单参数不合法");
        }
    }

    private String nextBusinessNo(String prefix, Long userId) {
        return prefix + Long.toUnsignedString(userId, 36)
                + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
