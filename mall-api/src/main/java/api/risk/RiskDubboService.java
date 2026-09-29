package api.risk;

import java.util.List;

public interface RiskDubboService {
    RiskDecisionResult evaluate(RiskEvaluateRequest request);

    RiskDecisionResult recordEvent(RiskEvaluateRequest request);

    void confirmEvent(String eventNo);

    SubjectRiskDTO getSubjectRisk(String subjectType, Long subjectId);

    RelationGraphDTO getRelations(Long userId, int depth);

    List<SensitiveWordDTO> getSensitiveWords();

    void confirmCommand(RiskCommandResultDTO result);
}
