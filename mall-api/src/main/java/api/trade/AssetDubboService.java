package api.trade;

import java.util.List;

public interface AssetDubboService {
    AssetReservationResult reserve(Long itemId, Integer quantity, String orderNo, Integer expireMinutes);

    AssetReservationResult reserve(Long itemId, Integer quantity, String orderNo, Integer expireMinutes,
                                   String idempotencyKey);

    boolean release(String orderNo);

    boolean release(String orderNo, String idempotencyKey);

    boolean confirm(String orderNo);

    boolean confirm(String orderNo, String idempotencyKey);

    List<CardSecretDTO> getSoldCardSecrets(String orderNo);

    boolean invalidateByOrderNo(String orderNo);

    boolean invalidateByOrderNo(String orderNo, String idempotencyKey);
}
