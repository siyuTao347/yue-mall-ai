package api.trade;

public interface MerchantDubboService {
    MerchantDTO getApprovedMerchantByUserId(Long userId);

    boolean isAdmin(Long userId);

    void completeOrder(Long merchantId, java.math.BigDecimal score);

    void refundOrder(Long merchantId);

    void disputeOrder(Long merchantId);
}
