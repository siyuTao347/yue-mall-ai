package api.agent.money.contract;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * 资金任务受众投影视图 (mall.money.view.v1)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MoneyTaskView implements Serializable {

    private String taskNo;
    private String caseNo;
    private String contextId;
    private String taskState;
    private String caseState;
    private Long taskVersion;
    private Long caseVersion;
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

