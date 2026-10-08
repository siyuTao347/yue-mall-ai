package com.example.user.dto;

import api.common.TimeRangeQuery;

public record WithdrawListQuery(
        String status,
        Long merchantId,
        Long userId,
        TimeRangeQuery timeRange
) {
}
