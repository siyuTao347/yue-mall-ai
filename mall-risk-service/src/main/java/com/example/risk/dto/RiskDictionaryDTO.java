package com.example.risk.dto;

import java.util.List;

public record RiskDictionaryDTO(
        List<RiskDictionaryItemDTO> caseStatus,
        List<RiskDictionaryItemDTO> commandStatus,
        List<RiskDictionaryItemDTO> riskLevel,
        List<RiskDictionaryItemDTO> scene,
        List<RiskDictionaryItemDTO> noteType,
        RiskCommandPolicyDTO commandPolicy
) {
}
