package api.risk;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiskRuleHitDTO implements Serializable {
    private String ruleCode;
    private String ruleName;
    private String action;
    private Integer riskScore;
    private Boolean autoCase;
    private Map<String, Object> actualValues;
}
