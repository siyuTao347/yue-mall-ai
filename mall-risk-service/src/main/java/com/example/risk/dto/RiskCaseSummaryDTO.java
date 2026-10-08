package com.example.risk.dto;

import java.time.LocalDateTime;

public record RiskCaseSummaryDTO(
        Long id,
        String caseNo,
        String decisionNo,
        String scene,
        String bizType,
        String bizNo,
        String subjectType,
        Long subjectId,
        String riskLevel,
        Integer riskScore,
        String status,
        Long assignedTo,
        String resolvedAction,
        String resolveReason,
        LocalDateTime resolvedTime,
        String lastCommandNo,
        String commandStatus,
        Integer commandRetryCount,
        Integer reopenCount,
        LocalDateTime createdTime,
        LocalDateTime updatedTime
) {
}
