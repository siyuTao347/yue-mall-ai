package com.example.risk.dto;

import java.time.LocalDateTime;
import java.util.Map;

public record RiskCaseNoteDTO(
        Long id,
        String noteType,
        String noteTypeText,
        String tone,
        Long operatorId,
        String operatorName,
        String content,
        String beforeStatus,
        String afterStatus,
        Map<String, Object> evidence,
        LocalDateTime createdTime
) {
}
