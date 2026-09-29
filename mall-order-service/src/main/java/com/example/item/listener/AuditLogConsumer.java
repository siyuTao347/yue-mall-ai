package com.example.item.listener;

import api.audit.AuditLogDTO;
import com.example.item.service.AuditLogService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.apache.rocketmq.spring.core.RocketMQPushConsumerLifecycleListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * RocketMQ 异步审计日志消费者
 *
 * 【简历核心亮点】：
 * 1. 独立线程池异步消费：日志入库消费与上游生产交易解耦，平滑消化突发流量，保护 MySQL 不被瞬间写入打垮。
 * 2. 幂等与容错：反序列化校验与入库兜底，异常时由 RocketMQ 重试机制进行保障。
 */
@Slf4j
@Service
@RocketMQMessageListener(
        topic = "audit-log-topic",
        consumerGroup = "audit-log-consumer-group"
)
public class AuditLogConsumer implements RocketMQListener<String>, RocketMQPushConsumerLifecycleListener {

    @Autowired
    private AuditLogService auditLogService;

    @Autowired
    private ObjectMapper objectMapper;

    @Override
    public void prepareStart(DefaultMQPushConsumer consumer) {
        consumer.setMqClientApiTimeout(10_000);
    }

    @Override
    public void onMessage(String message) {
        try {
            log.info("【RocketMQ审计日志消费者收到消息】开始异步持久化...");

            // 1. 反序列化 DTO
            AuditLogDTO logDTO = objectMapper.readValue(message, AuditLogDTO.class);

            // 2. 持久化入库
            auditLogService.saveAuditLog(logDTO);

            log.info("【RocketMQ审计日志消费并入库成功】Title: {}, TraceId: {}, CostTime: {}ms",
                    logDTO.getTitle(), logDTO.getTraceId(), logDTO.getCostTime());

        } catch (Exception e) {
            log.error("【RocketMQ审计日志消费处理异常】Payload: {}, Error: {}", message, e.getMessage(), e);
            // 抛出异常触发 RocketMQ 重试机制
            throw new RuntimeException("审计日志消费异常，触发MQ重试", e);
        }
    }
}
