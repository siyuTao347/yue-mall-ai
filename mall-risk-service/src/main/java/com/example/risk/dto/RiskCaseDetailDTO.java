package com.example.risk.dto;

import java.util.List;
import java.util.Map;

public record RiskCaseDetailDTO(
        RiskCaseSummaryDTO riskCase,
        RiskDecisionEvidenceDTO decision,
        RiskEventEvidenceDTO event,
        List<RiskCaseNoteDTO> notes,
        RiskCommandSnapshotDTO command,
        List<RiskRelationNodeDTO> relations,
        Map<String, Boolean> evidenceCompleteness
) {
}
