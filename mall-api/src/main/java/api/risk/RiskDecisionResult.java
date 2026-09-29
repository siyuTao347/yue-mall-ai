package api.risk;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiskDecisionResult implements Serializable {
    public static final String ACTION_PASS = "PASS";
    public static final String ACTION_WATCH = "WATCH";
    public static final String ACTION_VERIFY = "VERIFY";
    public static final String ACTION_LIMIT = "LIMIT";
    public static final String ACTION_MANUAL_REVIEW = "MANUAL_REVIEW";
    public static final String ACTION_REJECT = "REJECT";
    public static final String ACTION_FREEZE = "FREEZE";

    private String eventNo;
    private String decisionNo;
    private String action;
    private Integer riskScore;
    private String riskLevel;
    private Boolean degraded;
    private String message;
    private List<RiskRuleHitDTO> hitRules;

    public static RiskDecisionResult degraded(String eventNo) {
        return RiskDecisionResult.builder()
                .eventNo(eventNo)
                .action(ACTION_PASS)
                .riskScore(0)
                .riskLevel("LOW")
                .degraded(true)
                .message("风控服务暂不可用，已降级处理")
                .hitRules(List.of())
                .build();
    }

    public static RiskDecisionResult conflict(String eventNo) {
        return RiskDecisionResult.builder()
                .eventNo(eventNo)
                .action(ACTION_REJECT)
                .riskScore(100)
                .riskLevel("CRITICAL")
                .degraded(false)
                .message("RISK_EVENT_CONFLICT")
                .hitRules(List.of())
                .build();
    }
}
