package com.example.risk.listener;

import com.example.risk.service.RiskCommandDeadLetterService;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

@Component
@RocketMQMessageListener(topic = "%DLQ%item-risk-command-consumer-group",
        consumerGroup = "risk-item-command-dlq-consumer-group")
public class ItemRiskCommandDlqConsumer implements RocketMQListener<String> {
    private final RiskCommandDeadLetterService deadLetterService;

    public ItemRiskCommandDlqConsumer(RiskCommandDeadLetterService deadLetterService) {
        this.deadLetterService = deadLetterService;
    }

    @Override
    public void onMessage(String message) {
        deadLetterService.handle(message);
    }
}
