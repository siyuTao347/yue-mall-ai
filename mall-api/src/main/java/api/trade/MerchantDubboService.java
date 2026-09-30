package api.trade;

public interface MerchantDubboService {
    MerchantDTO getApprovedMerchantByUserId(Long userId);

    boolean isAdmin(Long userId);

    void completeOrder(Long merchantId, java.math.BigDecimal score, String orderNo);

    void completeOrder(Long merchantId, java.math.BigDecimal score, String orderNo, String idempotencyKey);

    void refundOrder(Long merchantId, String orderNo);

    void refundOrder(Long merchantId, String orderNo, String idempotencyKey);

    void disputeOrder(Long merchantId, String orderNo);

    void disputeOrder(Long merchantId, String orderNo, String idempotencyKey);
}
