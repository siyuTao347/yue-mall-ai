package com.example.risk.job;

import com.example.risk.mapper.RiskEventMapper;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
public class RiskMaintenanceJob {
    private final RiskEventMapper eventMapper;

    @Value("${risk.precheck-expire-minutes:10}")
    private int expireMinutes;

    public RiskMaintenanceJob(RiskEventMapper eventMapper) {
        this.eventMapper = eventMapper;
    }

    @XxlJob("riskMaintenanceJob")
    public void invalidateExpiredPrechecks() {
        LocalDateTime now = LocalDateTime.now();
        int invalidated = eventMapper.invalidateExpired(now.minusMinutes(expireMinutes), now);
        XxlJobHelper.log("风控预检查过期失效完成，处理数量: " + invalidated);
    }
}
