package com.example.risk.dto;

import api.risk.RiskRuleHitDTO;

import java.time.LocalDateTime;
import java.util.List;

public record RiskDecisionEvidenceDTO(
        String decisionNo,
        String action,
        String actionText,
        String riskLevel,
        String riskLevelText,
        Integer riskScore,
        String reason,
        List<RiskRuleHitDTO> hitRules,
        List<String> missingMetrics,
        Boolean degraded,
        LocalDateTime createdTime
) {
}
