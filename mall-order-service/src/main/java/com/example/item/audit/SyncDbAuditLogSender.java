package com.example.item.audit;

import api.audit.AuditLogDTO;
import api.audit.DeliveryMode;
import com.example.item.service.AuditLogService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 同步写库审计日志投递实现类
 *
 * 【对比说明与工程特点】：
 * 1. 同步落库：在当前 HTTP / 业务处理线程内直接执行 SQL INSERT 操作将日志落库。
 * 2. 耗时与开销：业务响应时间包含了数据库网络交互时间 (RTT) 与磁盘 I/O 耗时，并在大促高并发下竞争数据库连接池。
 * 3. 适用场景：适用于低并发后台管理系统、严格要求强一致强审计（同事务持久化）的场景，或作为压测对比 RocketMQ 异步解耦优势的基准实现。
 */
@Slf4j
@Component
public class SyncDbAuditLogSender implements AuditLogDeliveryStrategy {

    @Autowired
    private AuditLogService auditLogService;

    @Override
    public void send(AuditLogDTO logDTO) {
        if (logDTO == null) {
            return;
        }
        logDTO.setDeliveryMode(DeliveryMode.SYNC_DB.name());

        try {
            long startTime = System.currentTimeMillis();
            // 在当前线程同步写入数据库
            auditLogService.saveAuditLog(logDTO);
            long dbWriteCost = System.currentTimeMillis() - startTime;
            log.info("【同步写库审计日志完成】Title: {}, TraceId: {}, 同步DB耗时: {}ms",
                    logDTO.getTitle(), logDTO.getTraceId(), dbWriteCost);
        } catch (Exception e) {
            log.error("【同步写库审计日志持久化失败】Title: {}, TraceId: {}, Error: {}",
                    logDTO.getTitle(), logDTO.getTraceId(), e.getMessage(), e);
        }
    }

    @Override
    public DeliveryMode getMode() {
        return DeliveryMode.SYNC_DB;
    }
}
