package api.trade;

import java.util.List;

public interface AssetDubboService {
    AssetReservationResult reserve(Long itemId, Integer quantity, String orderNo, Integer expireMinutes);

    boolean release(String orderNo);

    boolean confirm(String orderNo);

    List<CardSecretDTO> getSoldCardSecrets(String orderNo);

    boolean invalidateByOrderNo(String orderNo);
}
