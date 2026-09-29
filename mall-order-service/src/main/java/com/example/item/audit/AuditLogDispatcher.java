package com.example.item.audit;

import api.audit.AuditLogDTO;
import api.audit.DeliveryMode;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 审计日志策略分发路由器 (Strategy Pattern Dispatcher)
 *
 * 负责根据注解参数（@AuditLog(deliveryMode=...)）或全局配置（audit.delivery-mode）
 * 动态路由到指定的投递策略（RocketMQ 异步投递 / 同步数据库落库）。
 */
@Slf4j
@Component
public class AuditLogDispatcher {

    @Autowired
    private List<AuditLogDeliveryStrategy> strategies;

    @Value("${audit.delivery-mode:ASYNC_MQ}")
    private String defaultDeliveryModeConfig;

    private final Map<DeliveryMode, AuditLogDeliveryStrategy> strategyMap = new EnumMap<>(DeliveryMode.class);

    @PostConstruct
    public void init() {
        for (AuditLogDeliveryStrategy strategy : strategies) {
            strategyMap.put(strategy.getMode(), strategy);
            log.info("【审计日志路由加载策略】模式: {} -> 处理器: {}", strategy.getMode(), strategy.getClass().getSimpleName());
        }
    }

    /**
     * 路由并分发审计日志
     *
     * @param logDTO        审计日志数据传输对象
     * @param preferredMode 方法注解上指定的投递模式
     */
    public void dispatch(AuditLogDTO logDTO, DeliveryMode preferredMode) {
        if (logDTO == null) {
            return;
        }

        // 1. 确定最终生效的投递模式
        DeliveryMode targetMode = determineDeliveryMode(preferredMode);

        // 2. 根据策略模式获取投递执行者
        AuditLogDeliveryStrategy strategy = strategyMap.get(targetMode);
        if (strategy == null) {
            // 降级兜底：默认使用 RocketMQ 异步或任一可用策略
            log.warn("【未找到指定投递策略】Mode: {}, 降级使用第一个可用策略", targetMode);
            strategy = strategyMap.values().stream().findFirst().orElse(null);
        }

        if (strategy != null) {
            strategy.send(logDTO);
        } else {
            log.error("【系统未配置任何审计日志投递策略】TraceId: {}", logDTO.getTraceId());
        }
    }

    private DeliveryMode determineDeliveryMode(DeliveryMode preferredMode) {
        if (preferredMode != null && preferredMode != DeliveryMode.DEFAULT) {
            return preferredMode;
        }

        try {
            return DeliveryMode.valueOf(defaultDeliveryModeConfig.trim().toUpperCase());
        } catch (Exception e) {
            log.warn("【解析配置 audit.delivery-mode 异常，默认降级为 ASYNC_MQ】配置值: {}", defaultDeliveryModeConfig);
            return DeliveryMode.ASYNC_MQ;
        }
    }
}
