package com.example.item.audit;

import api.audit.AuditLogDTO;
import api.audit.DeliveryMode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendCallback;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.apache.rocketmq.spring.support.RocketMQHeaders;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

/**
 * RocketMQ 异步审计日志投递实现类
 *
 * 【简历核心亮点】：
 * 1. 日志数据流与核心交易业务全链路解耦：核心接口无需执行耗时较长的 MySQL 磁盘 I/O，仅需毫秒级提交至 RocketMQ 消息队列。
 * 2. 削峰填谷与流量缓冲：大促并发洪峰下，日志消息在 MQ 中缓冲，避免高并发写日志打垮数据库或拖垮 HikariCP 连接池。
 * 3. 故障隔离与服务降级：投递采用异步回调 (asyncSend) 与全局容错捕获，若 MQ 发生偶发抖动或不可用，绝不抛出异常阻断核心业务事务。
 */
@Slf4j
@Component
public class RocketMQAsyncAuditLogSender implements AuditLogDeliveryStrategy {

    @Autowired
    private RocketMQTemplate rocketMQTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${audit.rocketmq.topic:audit-log-topic}")
    private String topic;

    @Value("${audit.rocketmq.tag:log}")
    private String tag;

    @Override
    public void send(AuditLogDTO logDTO) {
        if (logDTO == null) {
            return;
        }
        logDTO.setDeliveryMode(DeliveryMode.ASYNC_MQ.name());

        try {
            // 1. 序列化为 JSON 字符串
            String jsonPayload = objectMapper.writeValueAsString(logDTO);

            // 2. 构造 RocketMQ 消息，附带 TraceId 作为 Message Key 便于在 MQ 控制台检索排查
            String destination = topic + ":" + tag;
            Message<String> message = MessageBuilder.withPayload(jsonPayload)
                    .setHeader(RocketMQHeaders.KEYS, logDTO.getTraceId() != null ? logDTO.getTraceId() : "")
                    .build();

            // 3. 异步发送至 RocketMQ (不阻塞业务主线程，发送耗时通常 < 1ms)
            rocketMQTemplate.asyncSend(destination, message, new SendCallback() {
                @Override
                public void onSuccess(SendResult sendResult) {
                    log.debug("【RocketMQ审计日志投递成功】MsgId: {}, SendStatus: {}, TraceId: {}",
                            sendResult.getMsgId(), sendResult.getSendStatus(), logDTO.getTraceId());
                }

                @Override
                public void onException(Throwable throwable) {
                    // 异步发送失败回调，记录降级告警日志，绝不向外抛出影响主业务
                    log.error("【RocketMQ审计日志投递异常回调】TraceId: {}, Cause: {}",
                            logDTO.getTraceId(), throwable.getMessage(), throwable);
                }
            });

            log.info("【RocketMQ审计日志已异步投递至队列】Topic: {}, Title: {}, TraceId: {}",
                    destination, logDTO.getTitle(), logDTO.getTraceId());

        } catch (Exception e) {
            // 异常隔离与安全降级：保障主流程绝对安全
            log.error("【RocketMQ审计日志异步投递触发安全降级】Title: {}, TraceId: {}, Error: {}",
                    logDTO.getTitle(), logDTO.getTraceId(), e.getMessage(), e);
        }
    }

    @Override
    public DeliveryMode getMode() {
        return DeliveryMode.ASYNC_MQ;
    }
}
