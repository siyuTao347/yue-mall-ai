package api.trade;

import java.math.BigDecimal;

public interface FundDubboService {
    FundOperationResult freezeEscrow(String orderNo, Long sellerId, Long merchantId, BigDecimal amount);

    FundOperationResult refundEscrow(String orderNo, Long buyerId, BigDecimal amount);

    FundOperationResult settle(String orderNo, Long sellerId, Long merchantId,
                               BigDecimal orderAmount, BigDecimal feeAmount, BigDecimal sellerIncome);

    FundOperationResult withdrawFreeze(String withdrawNo, Long userId, Long merchantId, BigDecimal amount);

    FundOperationResult withdrawPayout(String withdrawNo, Long userId, Long merchantId, BigDecimal amount);

    FundOperationResult withdrawReject(String withdrawNo, Long userId, Long merchantId, BigDecimal amount);

    FundOperationResult settlePendingToAvailable(String orderNo, Long userId, BigDecimal amount);

    FundOperationResult payDeposit(String depositNo, Long merchantId, Long userId, BigDecimal amount);

    int reconcile();

    BigDecimal getPlatformEscrowAmount();
}
