package api.trade;

import java.math.BigDecimal;

public interface FundDubboService {
    FundOperationResult freezeEscrow(String orderNo, Long sellerId, Long merchantId, BigDecimal amount);

    FundOperationResult freezeEscrow(String orderNo, Long sellerId, Long merchantId, BigDecimal amount,
                                     String idempotencyKey);

    FundOperationResult refundEscrow(String orderNo, Long buyerId, BigDecimal amount);

    FundOperationResult refundEscrow(String orderNo, Long buyerId, BigDecimal amount, String idempotencyKey);

    FundOperationResult settle(String orderNo, Long sellerId, Long merchantId,
                               BigDecimal orderAmount, BigDecimal feeAmount, BigDecimal sellerIncome);

    FundOperationResult settle(String orderNo, Long sellerId, Long merchantId,
                               BigDecimal orderAmount, BigDecimal feeAmount, BigDecimal sellerIncome,
                               String idempotencyKey);

    FundOperationResult withdrawFreeze(String withdrawNo, Long userId, Long merchantId, BigDecimal amount);

    FundOperationResult withdrawPayout(String withdrawNo, Long userId, Long merchantId, BigDecimal amount);

    FundOperationResult withdrawReject(String withdrawNo, Long userId, Long merchantId, BigDecimal amount);

    FundOperationResult settlePendingToAvailable(String orderNo, Long userId, BigDecimal amount);

    FundOperationResult settlePendingToAvailable(String orderNo, Long userId, BigDecimal amount,
                                                 String idempotencyKey);

    FundOperationResult payDeposit(String depositNo, Long merchantId, Long userId, BigDecimal amount);

    int reconcile();

    BigDecimal getPlatformEscrowAmount();
}
