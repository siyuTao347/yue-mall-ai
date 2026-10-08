package com.example.risk.dto;

import java.time.LocalDateTime;

public record RiskCommandSnapshotDTO(
        String commandNo,
        String command,
        String commandText,
        String status,
        String statusText,
        String rawStatus,
        String tone,
        Integer retryCount,
        String lastError,
        LocalDateTime sentTime,
        LocalDateTime finishedTime
) {
}
