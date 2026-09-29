package com.example.item.job;

import com.example.item.service.TradeOrderService;
import com.example.item.service.TradeReconciliationService;
import api.trade.FundDubboService;
import org.apache.dubbo.config.annotation.DubboReference;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import org.springframework.stereotype.Component;

@Component
public class TradeOrderJobHandler {
    private static final int BATCH_SIZE = 100;

    private final TradeOrderService tradeOrderService;
    private final TradeReconciliationService reconciliationService;
    @DubboReference(timeout = 5000, retries = 0, check = false)
    private FundDubboService fundService;

    public TradeOrderJobHandler(TradeOrderService tradeOrderService,
                                TradeReconciliationService reconciliationService) {
        this.tradeOrderService = tradeOrderService;
        this.reconciliationService = reconciliationService;
    }

    @XxlJob("tradeCloseExpiredPaymentJob")
    public void closeExpiredPayments() {
        int closed = tradeOrderService.closeExpiredPayments(BATCH_SIZE);
        XxlJobHelper.log("支付超时关单完成，处理数量: " + closed);
    }

    @XxlJob("tradeDeliveryTimeoutJob")
    public void handleDeliveryTimeout() {
        int refunded = tradeOrderService.handleDeliveryTimeout(BATCH_SIZE);
        XxlJobHelper.log("交付超时处理完成，退款数量: " + refunded);
    }

    @XxlJob("tradeAutoConfirmJob")
    public void autoConfirm() {
        int confirmed = tradeOrderService.autoConfirm(BATCH_SIZE);
        XxlJobHelper.log("自动确认收货完成，处理数量: " + confirmed);
    }

    @XxlJob("tradeSettlementJob")
    public void settleOrders() {
        int settled = tradeOrderService.settleDueOrders(BATCH_SIZE);
        XxlJobHelper.log("结算任务完成，处理数量: " + settled);
    }

    @XxlJob("tradeFundReconciliationJob")
    public void reconcileFunds() {
        int tradeDiffs = reconciliationService.reconcile();
        int fundDiffs = fundService.reconcile();
        XxlJobHelper.log("资金对账完成，交易差异: " + tradeDiffs + "，资金差异: " + fundDiffs);
    }
}
