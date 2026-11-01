package com.example.item.money;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 订单资金领域事件发布器 (MoneyDomainEventPublisher)
 * 当订单与支付资金域产生关键领域事件（对账差异、支付尝试变更、退款状态变更、需人工干预）时，通过受控事件对外发布
 */
@Slf4j
@Component
public class MoneyDomainEventPublisher {

    public void publishReconDiffDetected(String diffNo, String orderNo, String diffType, long version) {
        String eventId = "EVT_RECON_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        log.info("发布资金领域事件 [ReconDiffDetected]: eventId={}, diffNo={}, orderNo={}, diffType={}, version={}",
                eventId, diffNo, orderNo, diffType, version);
    }

    public void publishPaymentAttemptChanged(String attemptNo, String orderNo, String status, long version) {
        String eventId = "EVT_ATTEMPT_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        log.info("发布资金领域事件 [PaymentAttemptChanged]: eventId={}, attemptNo={}, orderNo={}, status={}, version={}",
                eventId, attemptNo, orderNo, status, version);
    }

    public void publishRefundStatusChanged(String refundNo, String orderNo, String status, long version) {
        String eventId = "EVT_REFUND_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        log.info("发布资金领域事件 [RefundStatusChanged]: eventId={}, refundNo={}, orderNo={}, status={}, version={}",
                eventId, refundNo, orderNo, status, version);
    }

    public void publishTradeOperationManualRequired(String operationNo, String orderNo, String reason, long version) {
        String eventId = "EVT_OP_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        log.info("发布资金领域事件 [TradeOperationManualRequired]: eventId={}, opNo={}, orderNo={}, reason={}, version={}",
                eventId, operationNo, orderNo, reason, version);
    }
}

