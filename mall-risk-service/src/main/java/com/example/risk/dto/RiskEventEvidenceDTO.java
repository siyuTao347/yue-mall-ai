package com.example.risk.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

public record RiskEventEvidenceDTO(
        String eventNo,
        String scene,
        String sceneText,
        String eventType,
        String eventPhase,
        String bizType,
        String bizNo,
        String subject,
        BigDecimal amount,
        String ipHashMasked,
        String deviceHashMasked,
        Map<String, Object> context,
        LocalDateTime occurredTime,
        LocalDateTime confirmedTime
) {
}
