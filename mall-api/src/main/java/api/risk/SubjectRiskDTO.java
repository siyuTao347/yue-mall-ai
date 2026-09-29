package api.risk;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubjectRiskDTO implements Serializable {
    private String subjectType;
    private Long subjectId;
    private String riskStatus;
    private String riskLevel;
    private Integer riskScore;
    private String lastDecisionNo;
    private String riskReason;
}
