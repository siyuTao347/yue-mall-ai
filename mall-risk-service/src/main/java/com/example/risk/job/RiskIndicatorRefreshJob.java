package com.example.risk.job;

import com.example.risk.service.RiskIndicatorService;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class RiskIndicatorRefreshJob {
    private final RiskIndicatorService indicatorService;

    @Value("${risk.indicator.refresh-batch-size:100}")
    private int batchSize;

    public RiskIndicatorRefreshJob(RiskIndicatorService indicatorService) {
        this.indicatorService = indicatorService;
    }

    @XxlJob("riskIndicatorRefreshJob")
    public void refresh() {
        int count = indicatorService.refreshDueTasks(batchSize);
        XxlJobHelper.log("风控指标刷新完成，处理任务数量: " + count);
    }
}
