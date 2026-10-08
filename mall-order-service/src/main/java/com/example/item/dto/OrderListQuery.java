package com.example.item.dto;

import api.common.TimeRangeQuery;

public record OrderListQuery(
        String status,
        String disputeStatus,
        Long userId,
        Long merchantId,
        TimeRangeQuery timeRange
) {
}
