package com.example.risk.dto;

import lombok.Data;

@Data
public class UserMetricAggregate {
    private Long orderCount10m;
    private Long orderCount24h;
    private Long completedCount;
    private Long disputeCount7d;
    private Long loginFailCount10m;
}
