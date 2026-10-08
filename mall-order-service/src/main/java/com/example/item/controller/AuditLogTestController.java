package com.example.item.controller;

import api.audit.AuditLog;
import api.audit.BusinessType;
import api.audit.DeliveryMode;
import com.example.item.service.AuditLogService;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 审计日志演示与同步/异步写库对比控制器（仅本地与测试环境可用）。
 *
 * <p>生产环境 Bean 不创建；路径统一迁移到 /api/internal/dev/audit/**，网关无对应路由，
 * 服务内 InternalAccessFilter 默认拒绝 /api/internal/**；性能压测改由测试代码的
 * {@code AuditLogBenchmarkTest} 承担，不再通过 HTTP 触发。</p>
 */
@RestController
@RequestMapping("/api/internal/dev/audit")
@Profile({"local", "dev", "test", "default"})
public class AuditLogTestController {

    private final AuditLogService auditLogService;

    public AuditLogTestController(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    /**
     * 1. 异步审计日志测试 (RocketMQ 投递)
     * 请求示例: http://localhost:8083/api/audit/test-async?userId=1001&orderNo=ORD999888
     */
    @GetMapping("/test-async")
    @AuditLog(
            title = "订单服务-RocketMQ异步审计测试",
            businessType = BusinessType.INSERT,
            deliveryMode = DeliveryMode.ASYNC_MQ
    )
    public Map<String, Object> testAsyncAudit(
            @RequestParam(value = "userId", defaultValue = "1001") Long userId,
            @RequestParam(value = "orderNo", defaultValue = "ORD_ASYNC_888") String orderNo,
            @RequestParam(value = "password", defaultValue = "secret123") String password // 测试敏感参数脱敏
    ) {
        long startTime = System.currentTimeMillis();

        // 模拟核心业务逻辑处理 (5ms)
        mockBusinessWork(5);

        long cost = System.currentTimeMillis() - startTime;
        Map<String, Object> result = new HashMap<>();
        result.put("status", "SUCCESS");
        result.put("deliveryMode", "ASYNC_MQ (RocketMQ 异步解耦)");
        result.put("message", "业务执行完成，审计日志已异步投递至 MQ，核心接口毫秒级返回");
        result.put("businessCostTimeMs", cost);
        result.put("userId", userId);
        result.put("orderNo", orderNo);
        return result;
    }

    /**
     * 2. 同步审计日志测试 (直接写库)
     * 请求示例: http://localhost:8083/api/audit/test-sync?userId=1002&orderNo=ORD_SYNC_666
     */
    @GetMapping("/test-sync")
    @AuditLog(
            title = "订单服务-同步数据库审计测试",
            businessType = BusinessType.INSERT,
            deliveryMode = DeliveryMode.SYNC_DB
    )
    public Map<String, Object> testSyncAudit(
            @RequestParam(value = "userId", defaultValue = "1002") Long userId,
            @RequestParam(value = "orderNo", defaultValue = "ORD_SYNC_666") String orderNo,
            @RequestParam(value = "password", defaultValue = "secret123") String password
    ) {
        long startTime = System.currentTimeMillis();

        // 模拟核心业务逻辑处理 (5ms)
        mockBusinessWork(5);

        long cost = System.currentTimeMillis() - startTime;
        Map<String, Object> result = new HashMap<>();
        result.put("status", "SUCCESS");
        result.put("deliveryMode", "SYNC_DB (当前线程同步写库)");
        result.put("message", "业务执行完成，审计日志在当前线程同步写入 MySQL 完毕后返回");
        result.put("businessCostTimeMs", cost);
        result.put("userId", userId);
        result.put("orderNo", orderNo);
        return result;
    }

    /**
     * 3. 模拟业务异常捕获测试 (验证失败状态与异常堆栈提取)
     * 请求示例: http://localhost:8083/api/audit/test-exception?orderNo=ORD_FAIL_001
     */
    @GetMapping("/test-exception")
    @AuditLog(
            title = "订单服务-业务异常追溯测试",
            businessType = BusinessType.DELETE,
            deliveryMode = DeliveryMode.ASYNC_MQ
    )
    public Map<String, Object> testException(
            @RequestParam(value = "orderNo", defaultValue = "ORD_FAIL_001") String orderNo
    ) {
        // 模拟业务逻辑中抛出异常
        throw new IllegalArgumentException("【模拟业务异常】订单状态不合法，禁止删除！订单号: " + orderNo);
    }

    /**
     * 4. 查询最近持久化的审计日志列表
     * 请求示例: http://localhost:8083/api/audit/recent-logs?limit=10
     */
    @GetMapping("/recent-logs")
    public List<com.example.item.entity.AuditLog> getRecentLogs(@RequestParam(value = "limit", defaultValue = "10") int limit) {
        return auditLogService.listRecentLogs(limit);
    }

    private void mockBusinessWork(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
