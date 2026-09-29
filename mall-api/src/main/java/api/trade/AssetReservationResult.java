package api.trade;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AssetReservationResult implements Serializable {
    private boolean success;
    private String message;
    private String reservationNo;
    private ItemSnapshotDTO itemSnapshot;
    private List<CardSecretDTO> cardSecrets;

    public static AssetReservationResult fail(String message) {
        return AssetReservationResult.builder()
                .success(false)
                .message(message)
                .cardSecrets(new ArrayList<>())
                .build();
    }

    public static AssetReservationResult success(String reservationNo, ItemSnapshotDTO snapshot,
                                                  List<CardSecretDTO> cardSecrets) {
        return AssetReservationResult.builder()
                .success(true)
                .message("SUCCESS")
                .reservationNo(reservationNo)
                .itemSnapshot(snapshot)
                .cardSecrets(cardSecrets == null ? new ArrayList<>() : cardSecrets)
                .build();
    }
}
