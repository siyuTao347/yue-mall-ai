package com.example.risk.job;

import com.example.risk.mapper.RiskEventMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
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

    @Scheduled(fixedDelay = 60000)
    public void invalidateExpiredPrechecks() {
        LocalDateTime now = LocalDateTime.now();
        eventMapper.invalidateExpired(now.minusMinutes(expireMinutes), now);
    }
}
