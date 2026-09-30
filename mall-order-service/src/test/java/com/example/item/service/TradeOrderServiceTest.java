package com.example.item.service;

import api.risk.RiskDecisionResult;
import api.risk.RiskEvaluateRequest;
import api.risk.SensitiveWordScanner;
import api.trade.AssetDubboService;
import api.trade.FundDubboService;
import api.trade.ItemSnapshotDTO;
import api.trade.CardSecretDTO;
import com.example.item.dto.PaymentCallbackRequest;
import com.example.item.entity.OrderSettlement;
import com.example.item.entity.DeliveryRecord;
import com.example.item.entity.Dispute;
import com.example.item.entity.PaymentOrder;
import com.example.item.entity.TradeOrder;
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
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Consumer;

import org.springframework.transaction.TransactionStatus;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;

class TradeOrderServiceTest {
    private TradeOrderMapper orderMapper;
    private PaymentOrderMapper paymentMapper;
    private AssetDubboService assetService;
    private FundDubboService fundService;
    private TransactionTemplate transactionTemplate;
    private TradeStatusLogService statusLog;
    private DisputeMapper disputeMapper;
    private DeliveryRecordMapper deliveryMapper;
    private RiskClient riskClient;
    private TradeOrchestrationService orchestrationService;
    private TradeOrderService service;

    @BeforeEach
    void setUp() {
        orderMapper = mock(TradeOrderMapper.class);
        paymentMapper = mock(PaymentOrderMapper.class);
        assetService = mock(AssetDubboService.class);
        fundService = mock(FundDubboService.class);
        transactionTemplate = mock(TransactionTemplate.class);
        doAnswer(invocation -> {
            Consumer<TransactionStatus> action = invocation.getArgument(0);
            action.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
        doAnswer(invocation -> {
            TransactionCallback<?> action = invocation.getArgument(0);
            return action.doInTransaction(null);
        }).when(transactionTemplate).execute(any());
        statusLog = mock(TradeStatusLogService.class);
        disputeMapper = mock(DisputeMapper.class);
        deliveryMapper = mock(DeliveryRecordMapper.class);
        riskClient = mock(RiskClient.class);
        orchestrationService = mock(TradeOrchestrationService.class);
        when(riskClient.sensitiveWords()).thenReturn(SensitiveWordScanner.defaultWords());
        when(riskClient.evaluate(any(RiskEvaluateRequest.class))).thenReturn(pass());
        service = new TradeOrderService(orderMapper, paymentMapper,
                deliveryMapper, mock(DeliveryEvidenceMapper.class), disputeMapper,
                mock(DisputeMessageMapper.class), mock(ArbitrationMapper.class),
                mock(OrderSettlementMapper.class), mock(OrderReviewMapper.class),
                statusLog, new ObjectMapper(), transactionTemplate, riskClient,
                mock(OrderRiskStateWriter.class), orchestrationService);
        ReflectionTestUtils.setField(service, "assetService", assetService);
        ReflectionTestUtils.setField(service, "deliveryTimeoutMinutes", 30);
        ReflectionTestUtils.setField(service, "settleCooldownHours", 24);
    }

    @Test
    void callbackMarksLateSuccessWhenOrderCannotBePaid() {
        PaymentOrder payment = payment();
        when(paymentMapper.selectOne(any())).thenReturn(payment);
        when(orderMapper.selectOne(any())).thenReturn(order());
        when(orderMapper.markPaid(eq("TR1"), any(LocalDateTime.class), eq(30))).thenReturn(0);
        when(orderMapper.markCancellingFromWaitPay(eq("TR1"), any())).thenReturn(0);
        when(orderMapper.selectByOrderNoForUpdate("TR1")).thenReturn(order());

        String result = service.handleVerifiedCallback(callbackRequest(payment), payment);

        Assertions.assertEquals("PAY_EXPIRED_LATE_SUCCESS", result);
        verify(paymentMapper).markLateSuccess(eq("PAY1"), any());
        verify(fundService, never()).freezeEscrow(any(), any(), any(), any());
        verify(orchestrationService, never()).createPaymentConfirmTask(any(), any());
    }

    @Test
    void callbackKeepsSuccessfulPaymentStatusWhenCallbackIsDuplicated() {
        PaymentOrder payment = payment();
        payment.setStatus("SUCCESS");
        when(paymentMapper.selectOne(any())).thenReturn(payment);
        when(orderMapper.selectOne(any())).thenReturn(order());
        when(orderMapper.markPaid(eq("TR1"), any(LocalDateTime.class), eq(30))).thenReturn(0);

        String result = service.handleVerifiedCallback(callbackRequest(payment), payment);

        Assertions.assertEquals("PAY_CALLBACK_DUPLICATED", result);
        verify(paymentMapper, never()).markLateSuccess(eq("PAY1"), any());
        verify(fundService, never()).freezeEscrow(any(), any(), any(), any());
        verify(orchestrationService, never()).createPaymentConfirmTask(any(), any());
    }

    @Test
    void callbackDoesNotMarkLateSuccessWhenConcurrentPaymentWins() {
        PaymentOrder payment = payment();
        TradeOrder paidOrder = order();
        paidOrder.setOrderStatus("PAID");
        paidOrder.setPayStatus("SUCCESS");
        when(paymentMapper.selectOne(any())).thenReturn(payment);
        when(orderMapper.selectOne(any())).thenReturn(order());
        when(orderMapper.markPaid(eq("TR1"), any(LocalDateTime.class), eq(30))).thenReturn(0);
        when(orderMapper.selectByOrderNoForUpdate("TR1")).thenReturn(paidOrder);

        String result = service.handleVerifiedCallback(callbackRequest(payment), payment);

        Assertions.assertEquals("PAY_CALLBACK_DUPLICATED", result);
        verify(paymentMapper, never()).markLateSuccess(any(), any());
        verify(orderMapper, never()).markCancellingFromWaitPay(any(), any());
        verify(fundService, never()).freezeEscrow(any(), any(), any(), any());
        verify(orchestrationService, never()).createPaymentConfirmTask(any(), any());
    }

    @Test
    void createStoresPendingOrderAndTaskWithoutRemoteCalls() {
        TradeOrder result = service.create(10L, 1L, 1);

        Assertions.assertEquals("CREATE_PENDING", result.getOrderStatus());
        verify(orderMapper).insert(any(TradeOrder.class));
        verify(orchestrationService).createOrderCreateTask(
                any(TradeOrder.class), anyString(), any(), any(), any());
        verify(assetService, never()).reserve(any(), any(), any(), any());
        verify(assetService, never()).release(anyString());
        verify(riskClient, never()).evaluate(any(RiskEvaluateRequest.class));
    }

    @Test
    void settleRejectsOrderAmountNotGreaterThanMinFee() {
        ReflectionTestUtils.setField(service, "minFee", new BigDecimal("0.01"));
        TradeOrder order = order();
        order.setOrderStatus("CONFIRMED");
        order.setEscrowStatus("SETTLE_PENDING");
        order.setSettleAvailableTime(LocalDateTime.now().minusHours(1));
        order.setOrderAmount(new BigDecimal("0.01"));
        when(orderMapper.selectOne(any())).thenReturn(order);
        when(orderMapper.markSettling(eq("TR1"), any(LocalDateTime.class))).thenReturn(1);
        when(orderMapper.selectOne(any())).thenReturn(order);

        Assertions.assertThrows(IllegalArgumentException.class, () -> service.settle("TR1"));
        verify(fundService, never()).settle(any(), any(), any(), any(), any(), any());
        verify(orchestrationService, never()).createSettlementTask(any(), any(), any());
    }

    @Test
    void callbackRejectsTradeOrderAmountMismatch() {
        PaymentOrder payment = payment();
        payment.setAmount(new BigDecimal("99.00"));
        when(paymentMapper.selectOne(any())).thenReturn(payment);
        when(orderMapper.selectOne(any())).thenReturn(order());

        BigDecimal signedAmount = new BigDecimal("99.00");
        PaymentCallbackRequest request = callbackRequest(payment, signedAmount);
        Assertions.assertThrows(PaymentCallbackRejectedException.class,
                () -> service.handleVerifiedCallback(request, payment));
        verify(orderMapper, never()).markPaid(any(), any(), anyInt());
        verify(fundService, never()).freezeEscrow(any(), any(), any(), any());
        verify(orchestrationService, never()).createPaymentConfirmTask(any(), any());
    }

    @Test
    void callbackCreatesPaymentConfirmTaskAfterShortTransaction() {
        PaymentOrder payment = payment();
        TradeOrder order = order();
        when(paymentMapper.selectOne(any())).thenReturn(payment);
        when(orderMapper.selectOne(any())).thenReturn(order);
        when(orderMapper.markPayConfirming(eq("TR1"), any(LocalDateTime.class))).thenReturn(1);

        String result = service.handleVerifiedCallback(callbackRequest(payment), payment);

        Assertions.assertEquals("PAY_CALLBACK_ACCEPTED", result);
        verify(orderMapper).markPayConfirming(eq("TR1"), any());
        verify(paymentMapper).markSuccessPending(eq("PAY1"), any());
        verify(orchestrationService).createPaymentConfirmTask(order, "PAY1");
        verify(fundService, never()).freezeEscrow(any(), any(), any(), any());
        verify(assetService, never()).confirm(anyString());
    }

    @Test
    void buyerCanCancelBeforePaymentDeadline() {
        TradeOrder order = order();
        order.setPayDeadline(LocalDateTime.now().plusMinutes(10));
        when(orderMapper.selectOne(any())).thenReturn(order);
        when(orderMapper.markCancellingByBuyer(eq("TR1"), any(LocalDateTime.class))).thenReturn(1);

        int result = service.cancelUnpaidOrder(10L, "TR1", "买家取消未支付订单");

        Assertions.assertEquals(1, result);
        verify(paymentMapper).closeByOrder(eq("TR1"), any());
        verify(orchestrationService).createAssetReleaseTask(order, "买家取消未支付订单");
        verify(assetService, never()).release(anyString());
        verify(orderMapper, never()).markCancelled(any(), any());
    }

    @Test
    void confirmUsesSettleCooldownWhenMarkingOrderConfirmed() {
        TradeOrder order = order();
        order.setOrderStatus("DELIVERED");
        order.setDeliveryStatus("DELIVERED");
        when(orderMapper.selectOne(any())).thenReturn(order);
        when(orderMapper.markConfirmedByBuyer(eq("TR1"), any(LocalDateTime.class), eq(24))).thenReturn(1);

        service.confirm(10L, "TR1", "BUYER");

        verify(orderMapper).markConfirmedByBuyer(eq("TR1"), any(LocalDateTime.class), eq(24));
        verify(orchestrationService).createRiskEventTask(
                any(TradeOrder.class), eq("CONFIRM"), eq("CONFIRM"), any(), any(), any());
    }

    @Test
    void systemAutoConfirmOnlyProcessesDueOrders() {
        TradeOrder order = order();
        order.setOrderStatus("DELIVERED");
        order.setDeliveryStatus("VIEWED");
        when(orderMapper.selectOne(any())).thenReturn(order);
        when(orderMapper.markConfirmedForAutoConfirm(eq("TR1"), any(LocalDateTime.class), eq(24)))
                .thenReturn(1);

        service.confirm(null, "TR1", "SYSTEM");

        verify(orderMapper).markConfirmedForAutoConfirm(eq("TR1"), any(LocalDateTime.class), eq(24));
        verify(orderMapper, never()).markConfirmedByBuyer(any(), any(), anyInt());
        verify(orchestrationService).createRiskEventTask(
                any(TradeOrder.class), eq("CONFIRM"), eq("CONFIRM"), any(), any(), any());
    }

    @Test
    void deliverManualAssetRequiresContentBeforeChangingOrderStatus() {
        TradeOrder order = manualDeliveryOrder();
        when(orderMapper.selectOne(any())).thenReturn(order);

        Assertions.assertThrows(IllegalArgumentException.class,
                () -> service.deliver(20L, "TR1", " "));

        verify(orderMapper, never()).markDelivered(any(), any(), anyInt());
        verify(deliveryMapper, never()).insert(any(DeliveryRecord.class));
    }

    @Test
    void viewDeliveryMarksOrderAndRecordViewedForConfirmedOrder() {
        TradeOrder order = manualDeliveryOrder();
        order.setOrderStatus("CONFIRMED");
        order.setDeliveryStatus("DELIVERED");
        order.setPayStatus("SUCCESS");
        order.setEscrowStatus("SETTLE_PENDING");
        DeliveryRecord record = new DeliveryRecord();
        record.setOrderNo("TR1");
        record.setDeliveryContent("账号信息已离线发送");
        record.setStatus("DELIVERED");
        when(orderMapper.selectOne(any())).thenReturn(order);
        when(deliveryMapper.selectOne(any())).thenReturn(record);
        when(orderMapper.markDeliveryViewed(eq("TR1"), any(LocalDateTime.class))).thenReturn(1);
        when(deliveryMapper.markViewed(eq("TR1"), any(LocalDateTime.class))).thenReturn(1);

        DeliveryRecord result = service.viewDelivery(10L, "TR1");

        Assertions.assertEquals("VIEWED", result.getStatus());
        Assertions.assertNotNull(result.getFirstViewTime());
        verify(orderMapper).markDeliveryViewed(eq("TR1"), any(LocalDateTime.class));
        verify(deliveryMapper).markViewed(eq("TR1"), any(LocalDateTime.class));
        verify(statusLog).log(eq("TR1"), eq("DELIVERED"), eq("VIEWED"), eq("DELIVERY"),
                eq("BUYER"), eq(10L), eq("买家查看交付信息"));
    }

    @Test
    void viewSecretsAllowsConfirmedOrderAndWritesAuditLog() {
        TradeOrder order = order();
        order.setOrderStatus("CONFIRMED");
        order.setDeliveryStatus("VIEWED");
        order.setPayStatus("SUCCESS");
        order.setEscrowStatus("SETTLE_PENDING");
        when(orderMapper.selectOne(any())).thenReturn(order);
        when(assetService.getSoldCardSecrets("TR1")).thenReturn(List.of(
                CardSecretDTO.builder().id(1L).secretPlain("ABC").build()));
        when(orderMapper.markDeliveryViewed(eq("TR1"), any(LocalDateTime.class))).thenReturn(1);

        List<api.trade.CardSecretDTO> result = service.viewSecrets(10L, "TR1");

        Assertions.assertEquals("ABC", result.get(0).getSecretPlain());
        verify(statusLog).log(eq("TR1"), eq("VIEWED"), eq("VIEWED"), eq("SECRET"),
                eq("BUYER"), eq(10L), eq("买家查看卡密明文"));
    }

    @Test
    void deliveryTimeoutRefundsBuyerInsteadOfOpeningDispute() {
        TradeOrder order = order();
        order.setOrderStatus("PAID");
        order.setPayStatus("SUCCESS");
        order.setEscrowStatus("FROZEN");
        when(orderMapper.selectDeliveryTimeoutOrders(any(LocalDateTime.class), eq(100))).thenReturn(List.of(order));
        when(orderMapper.markRefunding(eq("TR1"), any(LocalDateTime.class))).thenReturn(1);

        int result = service.handleDeliveryTimeout(100);

        Assertions.assertEquals(1, result);
        verify(orchestrationService).createDeliveryTimeoutRefundTask(order);
        verify(fundService, never()).refundEscrow(any(), any(), any());
        verify(assetService, never()).invalidateByOrderNo(anyString());
        verify(orchestrationService).createDeliveryTimeoutRefundTask(order);
        verify(disputeMapper, never()).insert(any());
    }

    @Test
    void arbitrationReleaseAllSettlesDeliveredOrderToSeller() {
        TradeOrder order = order();
        order.setId(1L);
        order.setOrderStatus("DELIVERED");
        order.setPayStatus("SUCCESS");
        order.setDeliveryStatus("DELIVERED");
        order.setEscrowStatus("FROZEN");
        order.setDisputeStatus("ARBITRATING");
        Dispute dispute = new Dispute();
        dispute.setDisputeNo("DP1");
        dispute.setOrderNo("TR1");
        dispute.setBuyerId(10L);
        dispute.setSellerId(20L);
        dispute.setStatus("ARBITRATING");
        OrderSettlementMapper settlementMapper = mock(OrderSettlementMapper.class);
        when(disputeMapper.selectOne(any())).thenReturn(dispute);
        when(orderMapper.selectOne(any())).thenReturn(order);
        doAnswer(invocation -> {
            order.setOrderStatus("SETTLING");
            order.setEscrowStatus("SETTLE_PENDING");
            return 1;
        }).when(orderMapper).markSettlingByArbitration(eq("TR1"), any(LocalDateTime.class));

        TradeOrderService settleService = serviceWithSettlementMapper(settlementMapper);
        settleService.arbitrate(1L, "DP1", "RELEASE_ALL", "卖家已提供有效交付");

        verify(orderMapper).markSettlingByArbitration(eq("TR1"), any(LocalDateTime.class));
        verify(orchestrationService).createArbitrationReleaseTask(
                eq(order), eq("DP1"), eq(1L), eq("卖家已提供有效交付"),
                eq(new BigDecimal("2.00")), eq(new BigDecimal("98.00")));
        verify(fundService, never()).settle(any(), any(), any(), any(), any(), any());
        verify(fundService, never()).settlePendingToAvailable(any(), any(), any());
    }

    @Test
    void disputeBlocksDelivery() {
        TradeOrder order = order();
        order.setOrderStatus("PAID");
        order.setEscrowStatus("FROZEN");
        order.setDisputeStatus("ARBITRATING");
        when(orderMapper.selectOne(any())).thenReturn(order);

        Assertions.assertThrows(IllegalStateException.class,
                () -> service.deliver(20L, "TR1", "content"));
        verify(orderMapper, never()).markDelivered(any(), any(), anyInt());
    }

    @Test
    void getPaymentPassivelyClosesExpiredOrderBeforeRejectingPayment() {
        TradeOrder order = order();
        order.setPayDeadline(LocalDateTime.now().minusMinutes(1));
        when(orderMapper.selectOne(any())).thenReturn(order);
        when(orderMapper.markCancellingFromWaitPay(eq("TR1"), any(LocalDateTime.class))).thenReturn(1);

        Assertions.assertThrows(IllegalArgumentException.class, () -> service.getPayment("TR1"));

        verify(paymentMapper).markTimeout(eq("TR1"), any());
        verify(orchestrationService).createAssetReleaseTask(order, "支付超时被动关单");
        verify(assetService, never()).release(anyString());
        verify(orderMapper, never()).markCancelled(any(), any());
    }

    @Test
    void startPayRejectsExpiredOrderAfterPassiveClosure() {
        PaymentOrder payment = payment();
        payment.setStatus("INIT");
        TradeOrder order = order();
        order.setPayDeadline(LocalDateTime.now().minusMinutes(1));
        when(paymentMapper.selectOne(any())).thenReturn(payment);
        when(orderMapper.selectOne(any())).thenReturn(order);
        when(orderMapper.markCancellingFromWaitPay(eq("TR1"), any(LocalDateTime.class))).thenReturn(1);

        Assertions.assertThrows(IllegalArgumentException.class, () -> service.startPay("PAY1"));

        verify(paymentMapper).markTimeout(eq("TR1"), any());
        verify(orchestrationService).createAssetReleaseTask(order, "支付超时被动关单");
        verify(assetService, never()).release(anyString());
        verify(orderMapper, never()).markCancelled(any(), any());
        verify(paymentMapper, never()).startPaying(eq("TR1"), any());
    }

    @Test
    void settleResumesSettlingOrderWithoutDuplicateSettlement() {
        TradeOrder order = order();
        order.setId(1L);
        order.setOrderStatus("SETTLING");
        order.setEscrowStatus("SETTLE_PENDING");
        order.setSettleAvailableTime(LocalDateTime.now().minusHours(1));
        OrderSettlement settlement = new OrderSettlement();
        settlement.setOrderNo("TR1");
        settlement.setOrderAmount(order.getOrderAmount());
        settlement.setFeeAmount(new BigDecimal("2.00"));
        settlement.setSellerIncome(new BigDecimal("98.00"));
        settlement.setStatus("SUCCESS");
        settlement.setFundTransactionNo("FUND_SETTLE");
        when(orderMapper.selectOne(any())).thenReturn(order);
        OrderSettlementMapper settlementMapper = mock(OrderSettlementMapper.class);
        when(settlementMapper.selectOne(any())).thenReturn(settlement);

        TradeOrderService settleService = serviceWithSettlementMapper(settlementMapper);
        TradeOrder result = settleService.settle("TR1");

        Assertions.assertEquals("SETTLING", result.getOrderStatus());
        verify(orderMapper, never()).markSettling(any(), any());
        verify(fundService, never()).settle(any(), any(), any(), any(), any(), any());
        verify(fundService, never()).settlePendingToAvailable(any(), any(), any());
        verify(orchestrationService).createSettlementTask(
                order, new BigDecimal("2.00"), new BigDecimal("98.00"));
    }

    @Test
    void startPayRejectsOrderWaitingForManualRiskReview() {
        PaymentOrder payment = payment();
        payment.setStatus("INIT");
        TradeOrder order = order();
        order.setPayDeadline(LocalDateTime.now().plusMinutes(10));
        order.setRiskStatus("MANUAL_REVIEW");
        when(paymentMapper.selectOne(any())).thenReturn(payment);
        when(orderMapper.selectOne(any())).thenReturn(order);

        Assertions.assertThrows(IllegalStateException.class, () -> service.startPay("PAY1"));

        verify(paymentMapper, never()).startPaying(eq("TR1"), any());
    }

    @Test
    void settleRejectsFrozenOrderBeforeTouchingFunds() {
        TradeOrder order = order();
        order.setOrderStatus("CONFIRMED");
        order.setEscrowStatus("SETTLE_PENDING");
        order.setSettleAvailableTime(LocalDateTime.now().minusHours(1));
        order.setRiskStatus("FROZEN");
        when(orderMapper.selectOne(any())).thenReturn(order);

        Assertions.assertThrows(IllegalStateException.class, () -> service.settle("TR1"));

        verify(orderMapper, never()).markSettling(eq("TR1"), any());
        verify(fundService, never()).settle(any(), any(), any(), any(), any(), any());
        verify(orchestrationService, never()).createSettlementTask(any(), any(), any());
    }

    private TradeOrder order() {
        TradeOrder order = new TradeOrder();
        order.setOrderNo("TR1");
        order.setBuyerId(10L);
        order.setSellerId(20L);
        order.setMerchantId(30L);
        order.setOrderAmount(new BigDecimal("100.00"));
        order.setOrderStatus("WAIT_PAY");
        order.setPayStatus("INIT");
        order.setDeliveryStatus("WAIT_DELIVERY");
        order.setEscrowStatus("NONE");
        order.setDisputeStatus("NONE");
        order.setRiskStatus("NORMAL");
        order.setPayDeadline(LocalDateTime.now().minusMinutes(1));
        return order;
    }

    private PaymentOrder payment() {
        PaymentOrder payment = new PaymentOrder();
        payment.setPaymentNo("PAY1");
        payment.setOrderNo("TR1");
        payment.setAmount(new BigDecimal("100.00"));
        payment.setStatus("PAYING");
        payment.setCallbackTokenHash("token");
        return payment;
    }

    private TradeOrder manualDeliveryOrder() {
        TradeOrder order = order();
        order.setOrderStatus("PAID");
        order.setPayStatus("SUCCESS");
        order.setEscrowStatus("FROZEN");
        order.setItemSnapshot("{\"deliveryMode\":\"MANUAL_DELIVERY\"}");
        return order;
    }

    private TradeOrderService serviceWithSettlementMapper(OrderSettlementMapper settlementMapper) {
        TradeOrderService settleService = new TradeOrderService(orderMapper, paymentMapper,
                mock(DeliveryRecordMapper.class),
                mock(DeliveryEvidenceMapper.class), disputeMapper,
                mock(DisputeMessageMapper.class), mock(ArbitrationMapper.class),
                settlementMapper, mock(OrderReviewMapper.class), mock(TradeStatusLogService.class),
                new ObjectMapper(), transactionTemplate, riskClient, mock(OrderRiskStateWriter.class),
                orchestrationService);
        ReflectionTestUtils.setField(settleService, "assetService", assetService);
        return settleService;
    }

    private PaymentCallbackRequest callbackRequest(PaymentOrder payment) {
        return callbackRequest(payment, payment.getAmount());
    }

    private PaymentCallbackRequest callbackRequest(PaymentOrder payment, BigDecimal amount) {
        return new PaymentCallbackRequest(
                "CB1",
                payment.getPaymentNo(),
                payment.getOrderNo(),
                amount,
                "SUCCESS",
                System.currentTimeMillis(),
                "nonce",
                "signature",
                "{}"
        );
    }

    private RiskDecisionResult pass() {
        return RiskDecisionResult.builder()
                .action(RiskDecisionResult.ACTION_PASS)
                .riskScore(0)
                .riskLevel("LOW")
                .degraded(false)
                .message("pass")
                .hitRules(List.of())
                .build();
    }
}
