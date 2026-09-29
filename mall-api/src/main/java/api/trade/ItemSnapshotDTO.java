package api.trade;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ItemSnapshotDTO implements Serializable {
    private Long itemId;
    private Long sellerId;
    private Long merchantId;
    private String itemName;
    private String subTitle;
    private String imageUrl;
    private BigDecimal price;
    private String assetType;
    private String deliveryMode;
    private String riskNotice;
}
