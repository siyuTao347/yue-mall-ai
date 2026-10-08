package com.example.user.dto;

import api.common.TimeRangeQuery;

public record FundFlowListQuery(String accountType, TimeRangeQuery timeRange) {
}
