package com.example.risk.dto;

import java.time.LocalDateTime;

public record RiskCaseSummaryDTO(
        Long id,
        String caseNo,
        String decisionNo,
        String scene,
        String sceneText,
        String bizType,
        String bizNo,
        String subjectType,
        Long subjectId,
        String riskLevel,
        String riskLevelText,
        Integer riskScore,
        String status,
        String statusText,
        Long assignedTo,
        String resolvedAction,
        String resolvedActionText,
        String resolveReason,
        LocalDateTime resolvedTime,
        String lastCommandNo,
        String commandStatus,
        String commandStatusText,
        String commandTone,
        Integer commandRetryCount,
        String commandLastError,
        LocalDateTime commandSentTime,
        LocalDateTime commandFinishedTime,
        Long operationVersion,
        Integer reopenCount,
        LocalDateTime createdTime,
        LocalDateTime updatedTime
) {
}
