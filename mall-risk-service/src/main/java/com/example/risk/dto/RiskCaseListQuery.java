package com.example.risk.dto;

import api.common.TimeRangeQuery;

public record RiskCaseListQuery(
        String status,
        String scene,
        String riskLevel,
        String commandStatus,
        String subjectType,
        Long subjectId,
        String bizNo,
        TimeRangeQuery timeRange
) {
}
