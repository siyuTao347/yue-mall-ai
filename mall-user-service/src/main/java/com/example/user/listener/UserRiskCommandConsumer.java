package com.example.user.listener;

import api.risk.RiskCommandDTO;
import api.risk.RiskCommandResultDTO;
import com.example.user.service.RiskClient;
import com.example.user.service.UserRiskCommandHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.apache.rocketmq.spring.core.RocketMQPushConsumerLifecycleListener;
import org.springframework.stereotype.Component;

import java.util.Set;

@Slf4j
@Component
@RocketMQMessageListener(topic = "risk-command-topic",
        consumerGroup = "user-risk-command-consumer-group")
public class UserRiskCommandConsumer implements RocketMQListener<String>,
        RocketMQPushConsumerLifecycleListener {
    private static final Set<String> SUPPORTED_SCENES = Set.of("MERCHANT", "WITHDRAW");

    private final UserRiskCommandHandler handler;
    private final ObjectMapper objectMapper;
    private final RiskClient riskClient;

    public UserRiskCommandConsumer(UserRiskCommandHandler handler, ObjectMapper objectMapper,
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
            if (!SUPPORTED_SCENES.contains(command.getScene())) {
                log.info("忽略非用户域风控命令: scene={}, commandNo={}", command.getScene(),
                        command.getCommandNo());
                return;
            }
            boolean updated;
            try {
                updated = handler.handle(command);
            } catch (Exception e) {
                confirmFailure(command, e);
                log.error("用户域风控命令消费失败: message={}", message, e);
                throw new RuntimeException("用户域风控命令消费失败", e);
            }
            try {
                riskClient.confirmCommand(result(command, true, updated, null));
            } catch (Exception e) {
                log.warn("用户域风控命令执行结果回传失败: commandNo={}", command.getCommandNo(), e);
                throw new RuntimeException("用户域风控命令执行结果回传失败", e);
            }
            log.info("用户域风控命令处理完成: scene={}, commandNo={}, updated={}",
                    command.getScene(), command.getCommandNo(), updated);
        } catch (Exception e) {
            log.error("用户域风控命令消费失败: message={}", message, e);
            throw new RuntimeException("用户域风控命令消费失败", e);
        }
    }

    private void confirmFailure(RiskCommandDTO command, Exception error) {
        try {
            riskClient.confirmCommand(result(command, false, false, errorMessage(error)));
        } catch (Exception confirmError) {
            log.warn("用户域风控命令失败结果回传失败: commandNo={}", command.getCommandNo(), confirmError);
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
