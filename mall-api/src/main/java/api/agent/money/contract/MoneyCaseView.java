package api.agent.money.contract;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * 资金案件当前受众投影视图
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MoneyCaseView implements Serializable {

    private String caseNo;
    private String currentTaskNo;
    private String contextId;
    private String caseState;
    private String taskState;
    private Long caseVersion;
    private Long taskVersion;
    private Long projectionRevision;
    private String projectionHash;
    private String publicSummary;
    private List<Map<String, Object>> moneyFacts;
    private Map<String, Object> inputRequest;
    private List<String> allowedActions;
    private String reportRef;
    private Long reportRevision;
    private String updatedAt;
    private String coverage;
    private String freshness;
    private String resolutionOutcome;
}

