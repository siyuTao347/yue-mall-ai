package com.example.item.listener;

import com.example.item.service.TradeOrchestrationService;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@Slf4j
@RocketMQMessageListener(
        topic = "${trade.orchestration.topic:trade-orchestration-task-topic}",
        consumerGroup = "trade-orchestration-consumer-group"
)
public class TradeOrchestrationConsumer implements RocketMQListener<Map<String, Object>> {
    private final TradeOrchestrationService orchestrationService;

    public TradeOrchestrationConsumer(TradeOrchestrationService orchestrationService) {
        this.orchestrationService = orchestrationService;
    }

    @Override
    public void onMessage(Map<String, Object> message) {
        Object taskNo = message.get("taskNo");
        if (taskNo == null || String.valueOf(taskNo).isBlank()) {
            log.warn("trade orchestration message has no taskNo: {}", message);
            return;
        }
        orchestrationService.execute(String.valueOf(taskNo));
    }
}
