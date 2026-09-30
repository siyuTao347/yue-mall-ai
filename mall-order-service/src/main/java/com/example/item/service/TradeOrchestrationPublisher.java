package com.example.item.service;

import com.example.item.entity.TradeOrchestrationTask;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Map;

@Component
@Slf4j
public class TradeOrchestrationPublisher {
    private final ObjectProvider<RocketMQTemplate> rocketMQTemplateProvider;

    public TradeOrchestrationPublisher(ObjectProvider<RocketMQTemplate> rocketMQTemplateProvider) {
        this.rocketMQTemplateProvider = rocketMQTemplateProvider;
    }

    public void publishAfterCommit(TradeOrchestrationTask task, String topic) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publish(task, topic);
                }
            });
            return;
        }
        publish(task, topic);
    }

    private void publish(TradeOrchestrationTask task, String topic) {
        RocketMQTemplate template = rocketMQTemplateProvider.getIfAvailable();
        if (template == null) {
            return;
        }
        try {
            template.convertAndSend(topic, Map.of(
                    "taskNo", task.getTaskNo(),
                    "taskType", task.getTaskType(),
                    "bizNo", task.getBizNo()
            ));
        } catch (RuntimeException exception) {
            log.warn("trade orchestration message publish failed, taskNo={}", task.getTaskNo(), exception);
        }
    }
}
