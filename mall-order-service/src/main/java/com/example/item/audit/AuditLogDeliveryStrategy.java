package com.example.item.audit;

import api.audit.AuditLogDTO;
import api.audit.DeliveryMode;

/**
 * 审计日志投递策略接口 (Strategy Pattern)
 * 
 * 定义不同投递机制（如 RocketMQ 异步解耦投递 vs 同步写库落库）的标准行为。
 */
public interface AuditLogDeliveryStrategy {

    /**
     * 执行日志投递
     *
     * @param logDTO 审计日志数据对象
     */
    void send(AuditLogDTO logDTO);

    /**
     * 获取支持的投递模式
     *
     * @return 投递模式枚举
     */
    DeliveryMode getMode();
}
