package com.example.risk.dto;

import java.util.List;

public record RiskCommandPolicyDTO(
        int pollIntervalSeconds,
        int pollMaxDurationSeconds,
        boolean manualCompleteEnabled,
        int relationMaxNodes,
        List<String> fundRelatedCommands
) {
}
