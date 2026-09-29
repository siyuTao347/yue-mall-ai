package api.risk;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiskEvaluateRequest implements Serializable {
    private String eventNo;
    private String scene;
    private String eventType;
    private String bizType;
    private String bizNo;
    private Long userId;
    private Long merchantId;
    private Long itemId;
    private String orderNo;
    private String withdrawNo;
    private BigDecimal amount;
    private String ipHash;
    private String deviceHash;
    private Map<String, Object> payload;
    private List<SensitiveWordHitDTO> sensitiveHits;
}
