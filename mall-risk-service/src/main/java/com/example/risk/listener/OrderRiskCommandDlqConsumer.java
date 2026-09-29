package com.example.risk.listener;

import com.example.risk.service.RiskCommandDeadLetterService;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

@Component
@RocketMQMessageListener(topic = "%DLQ%order-risk-command-consumer-group",
        consumerGroup = "risk-order-command-dlq-consumer-group")
public class OrderRiskCommandDlqConsumer implements RocketMQListener<String> {
    private final RiskCommandDeadLetterService deadLetterService;

    public OrderRiskCommandDlqConsumer(RiskCommandDeadLetterService deadLetterService) {
        this.deadLetterService = deadLetterService;
    }

    @Override
    public void onMessage(String message) {
        deadLetterService.handle(message);
    }
}
