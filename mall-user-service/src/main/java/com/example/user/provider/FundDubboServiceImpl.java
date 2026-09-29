package com.example.user.provider;

import api.trade.FundDubboService;
import api.trade.FundOperationResult;
import com.example.user.service.FundReconciliationService;
import com.example.user.service.FundService;
import org.apache.dubbo.config.annotation.DubboService;

import java.math.BigDecimal;

@DubboService
public class FundDubboServiceImpl implements FundDubboService {
    private final FundService fundService;
    private final FundReconciliationService reconciliationService;

    public FundDubboServiceImpl(FundService fundService,
                                FundReconciliationService reconciliationService) {
        this.fundService = fundService;
        this.reconciliationService = reconciliationService;
    }

    @Override
    public FundOperationResult freezeEscrow(String orderNo, Long sellerId, Long merchantId, BigDecimal amount) {
        return fundService.freezeEscrow(orderNo, sellerId, merchantId, amount);
    }

    @Override
    public FundOperationResult refundEscrow(String orderNo, Long buyerId, BigDecimal amount) {
        return fundService.refundEscrow(orderNo, buyerId, amount);
    }

    @Override
    public FundOperationResult settle(String orderNo, Long sellerId, Long merchantId,
                                      BigDecimal orderAmount, BigDecimal feeAmount, BigDecimal sellerIncome) {
        return fundService.settle(orderNo, sellerId, merchantId, orderAmount, feeAmount, sellerIncome);
    }

    @Override
    public FundOperationResult withdrawFreeze(String withdrawNo, Long userId, Long merchantId, BigDecimal amount) {
        return fundService.withdrawFreeze(withdrawNo, userId, merchantId, amount);
    }

    @Override
    public FundOperationResult withdrawPayout(String withdrawNo, Long userId, Long merchantId, BigDecimal amount) {
        return fundService.withdrawPayout(withdrawNo, userId, merchantId, amount);
    }

    @Override
    public FundOperationResult withdrawReject(String withdrawNo, Long userId, Long merchantId, BigDecimal amount) {
        return fundService.withdrawReject(withdrawNo, userId, merchantId, amount);
    }

    @Override
    public FundOperationResult settlePendingToAvailable(String orderNo, Long userId, BigDecimal amount) {
        return fundService.settlePendingToAvailable(orderNo, userId, amount);
    }

    @Override
    public FundOperationResult payDeposit(String depositNo, Long merchantId, Long userId, BigDecimal amount) {
        return fundService.payDeposit(depositNo, merchantId, userId, amount);
    }

    @Override
    public int reconcile() {
        return reconciliationService.reconcile();
    }

    @Override
    public BigDecimal getPlatformEscrowAmount() {
        return fundService.getPlatformEscrowAmount();
    }
}
