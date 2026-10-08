package com.example.item.dto;

import api.common.TimeRangeQuery;

public record PendingItemListQuery(Long merchantId, String assetType, TimeRangeQuery timeRange) {
}
