package com.example.item.job;

import com.example.item.service.TradeOrchestrationService;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import org.springframework.stereotype.Component;

@Component
public class TradeOrchestrationJobHandler {
    private final TradeOrchestrationService orchestrationService;

    public TradeOrchestrationJobHandler(TradeOrchestrationService orchestrationService) {
        this.orchestrationService = orchestrationService;
    }

    @XxlJob("tradeOrchestrationRecoverJob")
    public void recover() {
        int recovered = orchestrationService.recover();
        XxlJobHelper.log("担保交易编排任务恢复完成，处理数量: " + recovered);
    }
}
