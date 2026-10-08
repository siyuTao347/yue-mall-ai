package com.example.item.dto;

import api.common.TimeRangeQuery;

public record DisputeListQuery(
        String status,
        String orderNo,
        TimeRangeQuery timeRange
) {
}
