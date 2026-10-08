package com.example.item.service;

import api.trade.AssetDubboService;
import api.trade.CardSecretDTO;
import api.trade.ItemSnapshotDTO;
import api.risk.RiskDecisionResult;
import api.risk.RiskEvaluateRequest;
import api.risk.RiskSupport;
import api.risk.SensitiveWordHitDTO;
import api.risk.SensitiveWordScanner;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import api.common.PageResult;
import com.example.item.entity.Arbitration;
import com.example.item.entity.DeliveryEvidence;
import com.example.item.entity.DeliveryRecord;
import com.example.item.entity.Dispute;
import com.example.item.entity.DisputeMessage;
import com.example.item.entity.OrderReview;
import com.example.item.entity.OrderSettlement;
import com.example.item.entity.PaymentOrder;
import com.example.item.entity.TradeOrder;
import com.example.item.entity.TradeOrderStatusLog;
import com.example.item.dto.DisputeListQuery;
import com.example.item.dto.DisputeSummaryDTO;
import com.example.item.dto.OrderListQuery;
import com.example.item.dto.TradeOrderSummaryDTO;
import com.example.item.dto.PaymentCallbackRequest;
import com.example.item.config.TradeOrderProperties;
import com.example.item.mapper.ArbitrationMapper;
import com.example.item.mapper.DeliveryEvidenceMapper;
import com.example.item.mapper.DeliveryRecordMapper;
import com.example.item.mapper.DisputeMapper;
import com.example.item.mapper.DisputeMessageMapper;
import com.example.item.mapper.OrderReviewMapper;
import com.example.item.mapper.OrderSettlementMapper;
import com.example.item.mapper.PaymentOrderMapper;
import com.example.item.mapper.TradeOrderMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.function.Consumer;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Slf4j
public class TradeOrderService {
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final TradeOrderMapper orderMapper;
    private final PaymentOrderMapper paymentMapper;
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
    private final TradeOrchestrationService orchestrationService;
    private static final List<String> SECRET_VIEWABLE_ORDER_STATUSES =
            List.of("DELIVERED", "CONFIRMED", "SETTLING", "SETTLED");
    private static final List<String> SECRET_VIEWABLE_ESCROW_STATUSES =
            List.of("FROZEN", "SETTLE_PENDING", "SETTLED");
    private static final List<String> RISK_BLOCKED_STATUSES =
            List.of("MANUAL_REVIEW", "FROZEN", "REJECTED");

    private BigDecimal feeRatePercent = new BigDecimal("2");
    private BigDecimal minFee = new BigDecimal("0.01");
    private int paymentExpireMinutes = 15;
    private int deliveryTimeoutMinutes = 30;
    private int autoConfirmHours = 24;
    private int settleCooldownHours = 24;
    private final Clock clock;

    @DubboReference(timeout = 5000, retries = 0, check = false)
    private AssetDubboService assetService;

    /**
     * 兼容测试与旧调用方的构造器：使用默认配置与系统时钟。
     */
    public TradeOrderService(TradeOrderMapper orderMapper, PaymentOrderMapper paymentMapper,
                             DeliveryRecordMapper deliveryMapper,
                             DeliveryEvidenceMapper evidenceMapper, DisputeMapper disputeMapper,
                             DisputeMessageMapper disputeMessageMapper, ArbitrationMapper arbitrationMapper,
                            OrderSettlementMapper settlementMapper, OrderReviewMapper reviewMapper,
                            TradeStatusLogService statusLog, ObjectMapper objectMapper,
                            TransactionTemplate transactionTemplate, RiskClient riskClient,
                            OrderRiskStateWriter riskStateWriter,
                            TradeOrchestrationService orchestrationService) {
        this(orderMapper, paymentMapper, deliveryMapper, evidenceMapper, disputeMapper,
                disputeMessageMapper, arbitrationMapper, settlementMapper, reviewMapper,
                statusLog, objectMapper, transactionTemplate, riskClient, riskStateWriter,
                orchestrationService, TradeOrderProperties.defaults(), Clock.systemDefaultZone());
    }

    @Autowired
    public TradeOrderService(TradeOrderMapper orderMapper, PaymentOrderMapper paymentMapper,
                             DeliveryRecordMapper deliveryMapper,
                             DeliveryEvidenceMapper evidenceMapper, DisputeMapper disputeMapper,
                             DisputeMessageMapper disputeMessageMapper, ArbitrationMapper arbitrationMapper,
                            OrderSettlementMapper settlementMapper, OrderReviewMapper reviewMapper,
                            TradeStatusLogService statusLog, ObjectMapper objectMapper,
                            TransactionTemplate transactionTemplate, RiskClient riskClient,
                            OrderRiskStateWriter riskStateWriter,
                            TradeOrchestrationService orchestrationService,
                            TradeOrderProperties orderProperties, Clock clock) {
        this.orderMapper = orderMapper;
        this.paymentMapper = paymentMapper;
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
        this.orchestrationService = orchestrationService;
        this.feeRatePercent = orderProperties.feeRatePercent();
        this.minFee = orderProperties.minFee();
        this.paymentExpireMinutes = orderProperties.paymentExpireMinutes();
        this.deliveryTimeoutMinutes = orderProperties.deliveryTimeoutMinutes();
        this.autoConfirmHours = orderProperties.autoConfirmHours();
        this.settleCooldownHours = orderProperties.settleCooldownHours();
        this.clock = clock;
    }

    @Transactional(rollbackFor = Exception.class)
    public TradeOrder create(Long buyerId, Long itemId, Integer quantity) {
        requireCreateParameters(buyerId, itemId, quantity);
        String orderNo = nextBusinessNo("TR", buyerId);
        String eventNo = RiskSupport.nextEventNo("ORDER");
        LocalDateTime now = LocalDateTime.now(clock);
        TradeOrder order = buildInitialOrder(buyerId, orderNo, itemId, quantity, now);
        orderMapper.insert(order);
        orchestrationService.createOrderCreateTask(
                order, eventNo, riskClient.ipHash(), riskClient.deviceHash(),
                createOrderPayload(itemId, quantity));
        statusLog.log(orderNo, null, "CREATE_PENDING", "ORDER", "BUYER", buyerId, "创建担保订单编排任务");
        return order;
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
        paymentMapper.startPaying(order.getOrderNo(), LocalDateTime.now(clock));
        return requirePaymentByNo(paymentNo);
    }

    @Transactional(rollbackFor = Exception.class)
    public String handleVerifiedCallback(PaymentCallbackRequest request, PaymentOrder payment) {
        String orderNo = request.orderNo();
        String paymentNo = request.paymentNo();
        if (!"SUCCESS".equals(request.result())) {
            paymentMapper.markFailed(paymentNo, LocalDateTime.now(clock));
            return "PAY_CALLBACK_ACCEPTED";
        }

        TradeOrder order = requireOrder(orderNo);
        requirePaymentAllowed(order);
        LocalDateTime now = LocalDateTime.now(clock);
        if (order.getOrderAmount() == null
                || request.amount().compareTo(order.getOrderAmount()) != 0) {
            throw new PaymentCallbackRejectedException(
                    "PAY_CALLBACK_AMOUNT_MISMATCH",
                    HttpStatus.BAD_REQUEST
            );
        }
        if ("SUCCESS".equals(payment.getStatus())) {
            return "PAY_CALLBACK_DUPLICATED";
        }
        if (orderMapper.markPayConfirming(orderNo, now) <= 0) {
            TradeOrder currentOrder = orderMapper.selectByOrderNoForUpdate(orderNo);
            if (currentOrder == null) {
                throw new IllegalStateException("订单不存在，支付回调处理失败");
            }
            if ("SUCCESS".equals(currentOrder.getPayStatus())
                    || "PAY_CONFIRMING".equals(currentOrder.getOrderStatus())) {
                return "PAY_CALLBACK_DUPLICATED";
            }
            paymentMapper.markLateSuccess(paymentNo, now);
            statusLog.log(orderNo, "WAIT_PAY", currentOrder.getOrderStatus(), "PAY", "SYSTEM", null,
                    "支付超时后渠道成功，订单保持当前状态，等待人工对账");
            return "PAY_EXPIRED_LATE_SUCCESS";
        }
        paymentMapper.markSuccessPending(paymentNo, now);
        statusLog.log(orderNo, "WAIT_PAY", "PAY_CONFIRMING", "ORDER", "SYSTEM", null,
                "支付回调校验通过，创建支付确认任务");
        statusLog.log(orderNo, "INIT", "SUCCESS_PENDING", "PAY", "SYSTEM", null, "支付单等待平台确认");
        orchestrationService.createPaymentConfirmTask(order, paymentNo);
        return "PAY_CALLBACK_ACCEPTED";
    }

    @Transactional(rollbackFor = Exception.class)
    public int closeExpiredOrder(String orderNo, String operatorType, Long operatorId, String reason) {
        LocalDateTime now = LocalDateTime.now(clock);
        if (orderMapper.markCancellingFromWaitPay(orderNo, now) <= 0) {
            return 0;
        }
        paymentMapper.markTimeout(orderNo, now);
        TradeOrder order = requireOrder(orderNo);
        statusLog.log(orderNo, "WAIT_PAY", "CANCELLING", "ORDER", operatorType, operatorId, reason);
        orchestrationService.createAssetReleaseTask(order, reason);
        return 1;
    }

    @Transactional(rollbackFor = Exception.class)
    public int cancelUnpaidOrder(Long buyerId, String orderNo, String reason) {
        TradeOrder order = requireOrder(orderNo);
        if (!order.getBuyerId().equals(buyerId)) {
            throw new IllegalArgumentException("只有买家可以取消订单");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        if (orderMapper.markCancellingByBuyer(orderNo, now) <= 0) {
            return 0;
        }
        paymentMapper.closeByOrder(orderNo, now);
        statusLog.log(orderNo, "WAIT_PAY", "CANCELLING", "ORDER", "BUYER", buyerId, reason);
        orchestrationService.createAssetReleaseTask(order, reason);
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
        LocalDateTime now = LocalDateTime.now(clock);
        if (orderMapper.markDelivered(orderNo, now, autoConfirmHours) <= 0) {
            throw new IllegalStateException("当前状态不能交付");
        }
        orchestrationService.createRiskEventTask(
                order, "DELIVERY", "DELIVER",
                riskClient.ipHash(), riskClient.deviceHash(),
                orderEventPayload(order, order.getRiskStatus()));
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
            LocalDateTime now = LocalDateTime.now(clock);
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
        LocalDateTime now = LocalDateTime.now(clock);
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
        orchestrationService.createRiskEventTask(
                order, "CONFIRM", "CONFIRM",
                riskClient.ipHash(), riskClient.deviceHash(),
                orderEventPayload(order, order.getRiskStatus()));
        statusLog.log(orderNo, "DELIVERED", "CONFIRMED", "ORDER", operatorType, operatorId, "确认收货");
        return requireOrder(orderNo);
    }

    @Transactional(rollbackFor = Exception.class)
    public TradeOrder settle(String orderNo) {
        LocalDateTime now = LocalDateTime.now(clock);
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
        BigDecimal fee = existing == null ? calculateFee(order.getOrderAmount()) : existing.getFeeAmount();
        BigDecimal income = existing == null
                ? order.getOrderAmount().subtract(fee) : existing.getSellerIncome();
        orderMapper.updateFeeAndIncomeByOrderNo(orderNo, fee, income, now);
        orchestrationService.createSettlementTask(order, fee, income);
        statusLog.log(orderNo, "CONFIRMED", "SETTLING", "ORDER", "SYSTEM", null, "结算冷却期结束，创建结算任务");
        return requireOrder(orderNo);
    }

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
        Dispute dispute = buildDispute(order, userId, type, reason, refundAmount);
        transactionTemplate.executeWithoutResult(status -> {
            if (orderMapper.markDispute(orderNo, "ARBITRATING", LocalDateTime.now(clock)) <= 0) {
                throw new IllegalArgumentException("订单售后状态已变化，请刷新后重试");
            }
            disputeMapper.insert(dispute);
            statusLog.log(orderNo, "NONE", "ARBITRATING", "DISPUTE", "USER", userId, "发起售后");
        });
        orchestrationService.createMerchantDisputeTask(order, dispute.getDisputeNo());
        orchestrationService.createRiskEventConfirmTask(order, eventNo);
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
        record.setCreatedTime(LocalDateTime.now(clock));
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
        evidence.setCreatedTime(LocalDateTime.now(clock));
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
            if (orderMapper.markRefunding(order.getOrderNo(), LocalDateTime.now(clock)) <= 0) {
                throw new IllegalStateException("订单状态变化，仲裁退款失败");
            }
            orchestrationService.createArbitrationRefundTask(order, disputeNo, adminId, reason);
            statusLog.log(order.getOrderNo(), "ARBITRATING", "REFUNDING", "ORDER",
                    "ADMIN", adminId, "仲裁全额退款任务已创建");
        } else if ("RELEASE_ALL".equals(result)) {
            if (!"DELIVERED".equals(order.getOrderStatus())) {
                throw new IllegalArgumentException("仅已交付订单可以仲裁全额放款");
            }
            if (orderMapper.markSettlingByArbitration(order.getOrderNo(), LocalDateTime.now(clock)) <= 0) {
                throw new IllegalStateException("订单状态变化，仲裁放款失败");
            }
            BigDecimal fee = calculateFee(order.getOrderAmount());
            BigDecimal income = order.getOrderAmount().subtract(fee);
            orderMapper.updateFeeAndIncomeByOrderNo(order.getOrderNo(), fee, income, LocalDateTime.now(clock));
            orchestrationService.createArbitrationReleaseTask(order, disputeNo, adminId, reason, fee, income);
            statusLog.log(order.getOrderNo(), "ARBITRATING", "SETTLING", "ORDER",
                    "ADMIN", adminId, "仲裁全额放款任务已创建");
        } else {
            throw new IllegalArgumentException("阶段一仅支持全额退款或全额放款");
        }

        return buildArbitration(dispute, result, reason, adminId, order);
    }

    public OrderReview review(Long buyerId, String orderNo, Integer score, String content) {
        TradeOrder order = requireOrder(orderNo);
        if (!order.getBuyerId().equals(buyerId)) {
            throw new IllegalArgumentException("只有买家可以评价");
        }
        if (!"SETTLED".equals(order.getOrderStatus())) {
            throw new IllegalStateException("订单结算后才能评价");
        }
        requireReviewScore(score);
        OrderReview review = new OrderReview();
        review.setOrderNo(orderNo);
        review.setBuyerId(buyerId);
        review.setMerchantId(order.getMerchantId());
        review.setScore(score);
        review.setContent(content);
        review.setCreatedTime(LocalDateTime.now(clock));
        transactionTemplate.executeWithoutResult(status -> reviewMapper.insert(review));
        orchestrationService.createMerchantCompleteTask(order, BigDecimal.valueOf(score));
        return review;
    }

    public PageResult<TradeOrderSummaryDTO> listOrders(OrderListQuery query, boolean admin, Long currentUserId,
                                                       int page, int pageSize) {
        if (query == null || (!admin && currentUserId == null)) {
            throw new IllegalArgumentException("订单查询条件不合法");
        }
        LambdaQueryWrapper<TradeOrder> wrapper = new LambdaQueryWrapper<TradeOrder>()
                .eq(query.status() != null, TradeOrder::getOrderStatus, query.status())
                .eq(query.disputeStatus() != null, TradeOrder::getDisputeStatus, query.disputeStatus())
                .ge(query.timeRange() != null && query.timeRange().fromTime() != null,
                        TradeOrder::getCreatedTime, query.timeRange().fromTime())
                .le(query.timeRange() != null && query.timeRange().toTime() != null,
                        TradeOrder::getCreatedTime, query.timeRange().toTime());
        if (admin) {
            wrapper.eq(query.userId() != null, TradeOrder::getBuyerId, query.userId())
                    .eq(query.merchantId() != null, TradeOrder::getMerchantId, query.merchantId());
        } else {
            wrapper.and(wrapperInner -> wrapperInner.eq(TradeOrder::getBuyerId, currentUserId)
                    .or().eq(TradeOrder::getSellerId, currentUserId));
        }
        wrapper.orderByDesc(TradeOrder::getCreatedTime).orderByDesc(TradeOrder::getId);
        Page<TradeOrder> result = orderMapper.selectPage(new Page<>(page, pageSize), wrapper);
        List<TradeOrderSummaryDTO> records = result.getRecords().stream().map(this::orderSummary).toList();
        return PageResult.of(records, result.getTotal(), page, pageSize);
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

    public PageResult<DisputeSummaryDTO> listDisputes(DisputeListQuery query, int page, int pageSize) {
        LambdaQueryWrapper<Dispute> wrapper = new LambdaQueryWrapper<Dispute>()
                .eq(query != null && query.status() != null, Dispute::getStatus, query == null ? null : query.status())
                .eq(query != null && query.orderNo() != null, Dispute::getOrderNo, query == null ? null : query.orderNo())
                .ge(query != null && query.timeRange() != null && query.timeRange().fromTime() != null,
                        Dispute::getCreatedTime, query == null || query.timeRange() == null
                                ? null : query.timeRange().fromTime())
                .le(query != null && query.timeRange() != null && query.timeRange().toTime() != null,
                        Dispute::getCreatedTime, query == null || query.timeRange() == null
                                ? null : query.timeRange().toTime())
                .orderByAsc(Dispute::getDeadlineTime)
                .orderByAsc(Dispute::getId);
        Page<Dispute> result = disputeMapper.selectPage(new Page<>(page, pageSize), wrapper);
        List<DisputeSummaryDTO> records = result.getRecords().stream().map(this::disputeSummary).toList();
        return PageResult.of(records, result.getTotal(), page, pageSize);
    }

    private TradeOrderSummaryDTO orderSummary(TradeOrder order) {
        return new TradeOrderSummaryDTO(order.getId(), order.getOrderNo(), order.getBuyerId(), order.getSellerId(),
                order.getMerchantId(), order.getItemId(), order.getQuantity(), order.getOrderAmount(),
                order.getFeeAmount(), order.getSellerIncome(), order.getOrderStatus(), order.getPayStatus(),
                order.getDeliveryStatus(), order.getEscrowStatus(), order.getDisputeStatus(), order.getPaymentNo(),
                order.getPayDeadline(), order.getDeliveryDeadline(), order.getDeliveredTime(), order.getConfirmedTime(),
                order.getAutoConfirmTime(), order.getSettleAvailableTime(), order.getSettledTime(),
                order.getItemSnapshot(), order.getCreatedTime(), order.getUpdatedTime());
    }

    private DisputeSummaryDTO disputeSummary(Dispute dispute) {
        return new DisputeSummaryDTO(dispute.getId(), dispute.getDisputeNo(), dispute.getOrderNo(),
                dispute.getBuyerId(), dispute.getSellerId(), dispute.getDisputeType(), dispute.getReason(),
                dispute.getProposedRefundAmount(), dispute.getStatus(), dispute.getDeadlineTime(),
                dispute.getCreatedTime(), dispute.getUpdatedTime());
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
        return processOrders(orderMapper.selectExpiredPayOrders(LocalDateTime.now(clock), limit),
                order -> closeExpiredOrder(order.getOrderNo(), "SYSTEM", null, "支付超时主动关单"));
    }

    public int autoConfirm(int limit) {
        return processOrders(orderMapper.selectAutoConfirmOrders(LocalDateTime.now(clock), limit),
                order -> confirm(null, order.getOrderNo(), "SYSTEM"));
    }

    public int settleDueOrders(int limit) {
        return processOrders(orderMapper.selectSettleableOrders(LocalDateTime.now(clock), limit),
                order -> settle(order.getOrderNo()));
    }

    public int handleDeliveryTimeout(int limit) {
        List<TradeOrder> orders = orderMapper.selectDeliveryTimeoutOrders(LocalDateTime.now(clock), limit);
        int processed = 0;
        for (TradeOrder order : orders) {
            try {
                transactionTemplate.executeWithoutResult(status -> {
                    if (orderMapper.markRefunding(order.getOrderNo(), LocalDateTime.now(clock)) <= 0) {
                        throw new IllegalStateException("订单已被其他任务处理，交付超时退款中断");
                    }
                    statusLog.log(order.getOrderNo(), "PAID", "REFUNDING", "ORDER",
                            "SYSTEM", null, "卖家超过交付截止时间，创建系统退款任务");
                    orchestrationService.createDeliveryTimeoutRefundTask(order);
                });
                processed++;
            } catch (RuntimeException e) {
                log.warn("交付超时退款失败，orderNo={}", order.getOrderNo(), e);
            }
        }
        return processed;
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

    private TradeOrder buildInitialOrder(Long buyerId, String orderNo, Long itemId,
                                         Integer quantity, LocalDateTime now) {
        TradeOrder order = new TradeOrder();
        order.setOrderNo(orderNo);
        order.setBuyerId(buyerId);
        order.setSellerId(buyerId);
        order.setMerchantId(buyerId);
        order.setItemId(itemId);
        order.setItemSnapshot("{}");
        order.setQuantity(quantity);
        order.setOrderAmount(BigDecimal.ZERO);
        order.setFeeAmount(BigDecimal.ZERO);
        order.setSellerIncome(BigDecimal.ZERO);
        order.setOrderStatus("CREATE_PENDING");
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

    private Map<String, Object> createOrderPayload(Long itemId, Integer quantity) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("itemId", itemId);
        payload.put("quantity", quantity);
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

    private Dispute buildDispute(TradeOrder order, Long userId, String type, String reason,
                                 BigDecimal refundAmount) {
        LocalDateTime now = LocalDateTime.now(clock);
        Dispute dispute = new Dispute();
        dispute.setDisputeNo(nextBusinessNo("DP", userId));
        dispute.setOrderNo(order.getOrderNo());
        dispute.setBuyerId(order.getBuyerId());
        dispute.setSellerId(order.getSellerId());
        dispute.setDisputeType(type);
        dispute.setReason(reason);
        dispute.setProposedRefundAmount(refundAmount == null ? order.getOrderAmount() : refundAmount);
        dispute.setStatus("ARBITRATING");
        dispute.setDeadlineTime(now.plusHours(24));
        dispute.setCreatedTime(now);
        dispute.setUpdatedTime(now);
        return dispute;
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
        arbitration.setCreatedTime(LocalDateTime.now(clock));
        return arbitration;
    }

    private void markSecretViewed(String orderNo) {
        LocalDateTime now = LocalDateTime.now(clock);
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
        return order.getPayDeadline() != null && order.getPayDeadline().isBefore(LocalDateTime.now(clock));
    }

    private boolean closeExpiredOrderIfPayExpired(TradeOrder order) {
        if (!isPayExpired(order)) {
            return false;
        }
        Integer closed = transactionTemplate.execute(status ->
                closeExpiredOrder(order.getOrderNo(), "SYSTEM", null, "支付超时被动关单"));
        return closed != null && closed > 0;
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

    private void requireCreateParameters(Long buyerId, Long itemId, Integer quantity) {
        if (buyerId == null || itemId == null || quantity == null || quantity <= 0) {
            throw new IllegalArgumentException("下单参数不合法");
        }
    }

    private void requireReviewScore(Integer score) {
        if (score == null || score < 1 || score > 5) {
            throw new IllegalArgumentException("评价分数必须在 1 到 5 之间");
        }
    }

    private String nextBusinessNo(String prefix, Long userId) {
        return prefix + Long.toUnsignedString(userId, 36)
                + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
