package com.example.item.dto;

import api.common.TimeRangeQuery;

public record OrderListQuery(
        String status,
        String disputeStatus,
        Long userId,
        Long merchantId,
        TimeRangeQuery timeRange,
        String view,
        String statusGroup,
        String orderNo
) {
    public OrderListQuery(String status, String disputeStatus, Long userId, Long merchantId, TimeRangeQuery timeRange) {
        this(status, disputeStatus, userId, merchantId, timeRange, null, null, null);
    }
}
