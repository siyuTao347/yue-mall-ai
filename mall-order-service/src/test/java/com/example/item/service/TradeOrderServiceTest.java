package com.example.item.service;

import api.trade.AssetDubboService;
import api.trade.AssetReservationResult;
import api.trade.FundDubboService;
import api.trade.FundOperationResult;
import api.trade.ItemSnapshotDTO;
import api.trade.MerchantDubboService;
import api.trade.CardSecretDTO;
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
import com.example.item.mapper.PaymentCallbackMapper;
import com.example.item.mapper.PaymentOrderMapper;
import com.example.item.mapper.TradeOrderMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
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
    private MerchantDubboService merchantService;
    private TransactionTemplate transactionTemplate;
    private TradeStatusLogService statusLog;
    private DisputeMapper disputeMapper;
    private DeliveryRecordMapper deliveryMapper;
    private TradeOrderService service;

    @BeforeEach
    void setUp() {
        orderMapper = mock(TradeOrderMapper.class);
        paymentMapper = mock(PaymentOrderMapper.class);
        assetService = mock(AssetDubboService.class);
        fundService = mock(FundDubboService.class);
        merchantService = mock(MerchantDubboService.class);
        transactionTemplate = mock(TransactionTemplate.class);
        doAnswer(invocation -> {
            Consumer<TransactionStatus> action = invocation.getArgument(0);
            action.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
        statusLog = mock(TradeStatusLogService.class);
        disputeMapper = mock(DisputeMapper.class);
        deliveryMapper = mock(DeliveryRecordMapper.class);
        service = new TradeOrderService(orderMapper, paymentMapper, mock(PaymentCallbackMapper.class),
                deliveryMapper, mock(DeliveryEvidenceMapper.class), disputeMapper,
                mock(DisputeMessageMapper.class), mock(ArbitrationMapper.class),
                mock(OrderSettlementMapper.class), mock(OrderReviewMapper.class),
                statusLog, new ObjectMapper(), transactionTemplate);
        ReflectionTestUtils.setField(service, "assetService", assetService);
        ReflectionTestUtils.setField(service, "fundService", fundService);
        ReflectionTestUtils.setField(service, "merchantService", merchantService);
        ReflectionTestUtils.setField(service, "deliveryTimeoutMinutes", 30);
        ReflectionTestUtils.setField(service, "settleCooldownHours", 24);
    }

    @Test
    void callbackRejectsOrderNoMismatch() {
        PaymentOrder payment = payment();
        when(paymentMapper.selectOne(any())).thenReturn(payment);

        String result = service.handleCallback("CB1", "PAY1", "TR2", payment.getAmount(),
                "SUCCESS", "signature", "{}");

        Assertions.assertEquals("ORDER_NO_MISMATCH", result);
        verify(paymentMapper, never()).markSuccess(eq("PAY1"), any());
    }

    @Test
    void callbackMarksLateSuccessWhenOrderCannotBePaid() {
        PaymentOrder payment = payment();
        when(paymentMapper.selectOne(any())).thenReturn(payment);
        when(orderMapper.selectOne(any())).thenReturn(order());
        when(orderMapper.markPaid(eq("TR1"), any(LocalDateTime.class), eq(30))).thenReturn(0);
        when(orderMapper.markCancellingFromWaitPay(eq("TR1"), any())).thenReturn(0);
        when(orderMapper.selectByOrderNoForUpdate("TR1")).thenReturn(order());

        String result = service.handleCallback("CB1", "PAY1", "TR1", payment.getAmount(),
                "SUCCESS", signature(payment), "{}");

        Assertions.assertEquals("PAY_EXPIRED_LATE_SUCCESS", result);
        verify(paymentMapper).markLateSuccess(eq("PAY1"), any());
        verify(fundService, never()).freezeEscrow(any(), any(), any(), any());
    }

    @Test
    void callbackKeepsSuccessfulPaymentStatusWhenCallbackIsDuplicated() {
        PaymentOrder payment = payment();
        payment.setStatus("SUCCESS");
        when(paymentMapper.selectOne(any())).thenReturn(payment);
        when(orderMapper.selectOne(any())).thenReturn(order());
        when(orderMapper.markPaid(eq("TR1"), any(LocalDateTime.class), eq(30))).thenReturn(0);

        String result = service.handleCallback("CB1", "PAY1", "TR1", payment.getAmount(),
                "SUCCESS", signature(payment), "{}");

        Assertions.assertEquals("CALLBACK_DUPLICATED", result);
        verify(paymentMapper, never()).markLateSuccess(eq("PAY1"), any());
        verify(fundService, never()).freezeEscrow(any(), any(), any(), any());
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

        String result = service.handleCallback("CB1", "PAY1", "TR1", payment.getAmount(),
                "SUCCESS", signature(payment), "{}");

        Assertions.assertEquals("CALLBACK_DUPLICATED", result);
        verify(paymentMapper, never()).markLateSuccess(any(), any());
        verify(orderMapper, never()).markCancellingFromWaitPay(any(), any());
        verify(fundService, never()).freezeEscrow(any(), any(), any(), any());
    }

    @Test
    void createRejectsOrderAmountNotGreaterThanMinFee() {
        ReflectionTestUtils.setField(service, "minFee", new BigDecimal("0.01"));
        ItemSnapshotDTO snapshot = new ItemSnapshotDTO();
        snapshot.setPrice(new BigDecimal("0.01"));
        when(assetService.reserve(any(), any(), any(), any()))
                .thenReturn(AssetReservationResult.success("RSV1", snapshot, List.of()));

        Assertions.assertThrows(IllegalArgumentException.class,
                () -> service.create(10L, 1L, 1));
        verify(assetService).release(anyString());
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
    }

    @Test
    void callbackRejectsTradeOrderAmountMismatch() {
        PaymentOrder payment = payment();
        payment.setAmount(new BigDecimal("99.00"));
        when(paymentMapper.selectOne(any())).thenReturn(payment);
        when(orderMapper.selectOne(any())).thenReturn(order());

        BigDecimal signedAmount = new BigDecimal("99.00");
        String result = service.handleCallback("CB1", "PAY1", "TR1", signedAmount,
                "SUCCESS", signatureWithAmount(payment, signedAmount), "{}");

        Assertions.assertEquals("AMOUNT_MISMATCH", result);
        verify(orderMapper, never()).markPaid(any(), any(), anyInt());
        verify(fundService, never()).freezeEscrow(any(), any(), any(), any());
    }

    @Test
    void callbackFreezesEscrowAfterPaymentAndAssetConfirm() {
        PaymentOrder payment = payment();
        TradeOrder order = order();
        when(paymentMapper.selectOne(any())).thenReturn(payment);
        when(orderMapper.selectOne(any())).thenReturn(order);
        when(orderMapper.markPaid(eq("TR1"), any(LocalDateTime.class), eq(30))).thenReturn(1);
        when(fundService.freezeEscrow("TR1", 20L, 30L, order.getOrderAmount()))
                .thenReturn(FundOperationResult.success("FUND1"));
        when(assetService.confirm("TR1")).thenReturn(true);
        when(orderMapper.markEscrowFrozen(eq("TR1"), any(LocalDateTime.class))).thenReturn(1);

        String result = service.handleCallback("CB1", "PAY1", "TR1", payment.getAmount(),
                "SUCCESS", signature(payment), "{}");

        Assertions.assertEquals("SUCCESS", result);
        verify(orderMapper).markEscrowFrozen(eq("TR1"), any(LocalDateTime.class));
    }

    @Test
    void buyerCanCancelBeforePaymentDeadline() {
        TradeOrder order = order();
        order.setPayDeadline(LocalDateTime.now().plusMinutes(10));
        when(orderMapper.selectOne(any())).thenReturn(order);
        when(orderMapper.markCancellingByBuyer(eq("TR1"), any(LocalDateTime.class))).thenReturn(1);
        when(assetService.release("TR1")).thenReturn(true);

        int result = service.cancelUnpaidOrder(10L, "TR1", "买家取消未支付订单");

        Assertions.assertEquals(1, result);
        verify(paymentMapper).closeByOrder(eq("TR1"), any());
        verify(assetService).release("TR1");
        verify(orderMapper).markCancelled(eq("TR1"), any());
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
        when(fundService.refundEscrow("TR1", 10L, order.getOrderAmount()))
                .thenReturn(FundOperationResult.success("REFUND1"));
        when(assetService.invalidateByOrderNo("TR1")).thenReturn(true);
        when(orderMapper.markRefunded(eq("TR1"), any(LocalDateTime.class))).thenReturn(1);
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            org.springframework.transaction.support.TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });

        int result = service.handleDeliveryTimeout(100);

        Assertions.assertEquals(1, result);
        verify(fundService).refundEscrow("TR1", 10L, order.getOrderAmount());
        verify(assetService).invalidateByOrderNo("TR1");
        verify(merchantService).refundOrder(30L);
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
        when(fundService.settle(eq("TR1"), eq(20L), eq(30L), eq(order.getOrderAmount()),
                eq(new BigDecimal("2.00")), eq(new BigDecimal("98.00"))))
                .thenReturn(FundOperationResult.success("FUND_SETTLE"));
        when(fundService.settlePendingToAvailable(eq("TR1"), eq(20L), eq(new BigDecimal("98.00"))))
                .thenReturn(FundOperationResult.success("FUND_AVAILABLE"));
        doAnswer(invocation -> {
            order.setOrderStatus("SETTLED");
            order.setEscrowStatus("SETTLED");
            return 1;
        }).when(orderMapper).markSettled(eq("TR1"), any(LocalDateTime.class));

        TradeOrderService settleService = serviceWithSettlementMapper(settlementMapper);
        settleService.arbitrate(1L, "DP1", "RELEASE_ALL", "卖家已提供有效交付");

        verify(orderMapper).markSettlingByArbitration(eq("TR1"), any(LocalDateTime.class));
        verify(fundService).settle(eq("TR1"), eq(20L), eq(30L), eq(order.getOrderAmount()),
                eq(new BigDecimal("2.00")), eq(new BigDecimal("98.00")));
        verify(fundService).settlePendingToAvailable(eq("TR1"), eq(20L), eq(new BigDecimal("98.00")));
        verify(orderMapper).markSettled(eq("TR1"), any(LocalDateTime.class));
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
        when(assetService.release("TR1")).thenReturn(true);
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            org.springframework.transaction.support.TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });

        Assertions.assertThrows(IllegalArgumentException.class, () -> service.getPayment("TR1"));

        verify(paymentMapper).markTimeout(eq("TR1"), any());
        verify(assetService).release("TR1");
        verify(orderMapper).markCancelled(eq("TR1"), any());
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
        when(assetService.release("TR1")).thenReturn(true);
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            org.springframework.transaction.support.TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });

        Assertions.assertThrows(IllegalArgumentException.class, () -> service.startPay("PAY1"));

        verify(paymentMapper).markTimeout(eq("TR1"), any());
        verify(assetService).release("TR1");
        verify(orderMapper).markCancelled(eq("TR1"), any());
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
        when(fundService.settlePendingToAvailable("TR1", 20L, settlement.getSellerIncome()))
                .thenReturn(FundOperationResult.success("FUND_AVAILABLE"));
        when(orderMapper.markSettled(eq("TR1"), any(LocalDateTime.class))).thenReturn(1);

        TradeOrderService settleService = serviceWithSettlementMapper(settlementMapper);
        TradeOrder result = settleService.settle("TR1");

        Assertions.assertEquals("SETTLING", result.getOrderStatus());
        verify(orderMapper, never()).markSettling(any(), any());
        verify(fundService, never()).settle(any(), any(), any(), any(), any(), any());
        verify(fundService).settlePendingToAvailable("TR1", 20L, settlement.getSellerIncome());
        verify(orderMapper).markSettled(eq("TR1"), any());
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
                mock(PaymentCallbackMapper.class), mock(DeliveryRecordMapper.class),
                mock(DeliveryEvidenceMapper.class), disputeMapper,
                mock(DisputeMessageMapper.class), mock(ArbitrationMapper.class),
                settlementMapper, mock(OrderReviewMapper.class), mock(TradeStatusLogService.class),
                new ObjectMapper(), transactionTemplate);
        ReflectionTestUtils.setField(settleService, "assetService", assetService);
        ReflectionTestUtils.setField(settleService, "fundService", fundService);
        ReflectionTestUtils.setField(settleService, "merchantService", merchantService);
        return settleService;
    }

    private String signature(PaymentOrder payment) {
        return signatureWithAmount(payment, payment.getAmount());
    }

    private String signatureWithAmount(PaymentOrder payment, BigDecimal amount) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String raw = payment.getPaymentNo() + "|" + amount + "|" + payment.getCallbackTokenHash();
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
