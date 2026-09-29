package com.example.item.listener;

import api.risk.RiskCommandDTO;
import api.risk.RiskCommandResultDTO;
import com.example.item.service.RiskClient;
import com.example.item.service.ItemRiskCommandHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.apache.rocketmq.spring.core.RocketMQPushConsumerLifecycleListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RocketMQMessageListener(topic = "risk-command-topic",
        consumerGroup = "item-risk-command-consumer-group")
public class ItemRiskCommandConsumer implements RocketMQListener<String>,
        RocketMQPushConsumerLifecycleListener {
    private final ItemRiskCommandHandler handler;
    private final ObjectMapper objectMapper;
    private final RiskClient riskClient;

    public ItemRiskCommandConsumer(ItemRiskCommandHandler handler, ObjectMapper objectMapper,
                                   RiskClient riskClient) {
        this.handler = handler;
        this.objectMapper = objectMapper;
        this.riskClient = riskClient;
    }

    @Override
    public void prepareStart(DefaultMQPushConsumer consumer) {
        consumer.setMqClientApiTimeout(10_000);
    }

    @Override
    public void onMessage(String message) {
        try {
            RiskCommandDTO command = objectMapper.readValue(message, RiskCommandDTO.class);
            if (!"LISTING".equals(command.getScene())) {
                log.info("忽略非商品域风控命令: scene={}, commandNo={}", command.getScene(),
                        command.getCommandNo());
                return;
            }
            boolean updated;
            try {
                updated = handler.handle(command);
            } catch (Exception e) {
                confirmFailure(command, e);
                log.error("商品风控命令消费失败: message={}", message, e);
                throw new RuntimeException("商品风控命令消费失败", e);
            }
            try {
                riskClient.confirmCommand(result(command, true, updated, null));
            } catch (Exception e) {
                log.warn("商品风控命令执行结果回传失败: commandNo={}", command.getCommandNo(), e);
                throw new RuntimeException("商品风控命令执行结果回传失败", e);
            }
            log.info("商品风控命令处理完成: commandNo={}, itemId={}, updated={}",
                    command.getCommandNo(), command.getItemId(), updated);
        } catch (Exception e) {
            log.error("商品风控命令消费失败: message={}", message, e);
            throw new RuntimeException("商品风控命令消费失败", e);
        }
    }

    private void confirmFailure(RiskCommandDTO command, Exception error) {
        try {
            riskClient.confirmCommand(result(command, false, false, errorMessage(error)));
        } catch (Exception confirmError) {
            log.warn("商品风控命令失败结果回传失败: commandNo={}", command.getCommandNo(), confirmError);
        }
    }

    private RiskCommandResultDTO result(RiskCommandDTO command, boolean success, boolean updated,
                                        String message) {
        return RiskCommandResultDTO.builder()
                .commandNo(command.getCommandNo())
                .caseNo(command.getCaseNo())
                .scene(command.getScene())
                .success(success)
                .updated(updated)
                .message(message)
                .build();
    }

    private String errorMessage(Exception error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }
}
