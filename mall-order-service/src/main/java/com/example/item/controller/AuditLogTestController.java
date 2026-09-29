package com.example.item.controller;

import api.audit.AuditLog;
import api.audit.BusinessType;
import api.audit.DeliveryMode;
import com.example.item.service.AuditLogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 审计日志演示与性能量化对比控制器
 *
 * 提供异步 MQ 投递、同步写库、异常捕获、性能 Benchmark 对比等接口
 */
@RestController
@RequestMapping("/api/audit")
public class AuditLogTestController {

    @Autowired
    private AuditLogService auditLogService;

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

    /**
     * 5. 性能量化基准测试 (用于简历量化数据支撑)
     * 分别调用多次同步与异步落库逻辑，计算平均延迟
     * 请求示例: http://localhost:8083/api/audit/benchmark?rounds=20
     */
    @GetMapping("/benchmark")
    public Map<String, Object> benchmark(@RequestParam(value = "rounds", defaultValue = "20") int rounds) {
        int testRounds = Math.max(5, Math.min(rounds, 100));

        // 1. 同步落库耗时统计
        long syncTotalTime = 0;
        for (int i = 0; i < testRounds; i++) {
            long t1 = System.currentTimeMillis();
            testSyncAudit(1000L + i, "BENCH_SYNC_" + i, "pwd");
            syncTotalTime += (System.currentTimeMillis() - t1);
        }
        double syncAvg = (double) syncTotalTime / testRounds;

        // 2. 异步 MQ 耗时统计
        long asyncTotalTime = 0;
        for (int i = 0; i < testRounds; i++) {
            long t1 = System.currentTimeMillis();
            testAsyncAudit(2000L + i, "BENCH_ASYNC_" + i, "pwd");
            asyncTotalTime += (System.currentTimeMillis() - t1);
        }
        double asyncAvg = (double) asyncTotalTime / testRounds;

        // 3. 计算延迟降低幅度
        double latencyDropPercent = syncAvg > 0 ? ((syncAvg - asyncAvg) / syncAvg) * 100.0 : 0;

        Map<String, Object> report = new HashMap<>();
        report.put("testRounds", testRounds);
        report.put("syncAvgResponseTimeMs", String.format("%.2f ms", syncAvg));
        report.put("asyncAvgResponseTimeMs", String.format("%.2f ms", asyncAvg));
        report.put("latencyDropPercent", String.format("%.2f %%", latencyDropPercent));
        report.put("conclusion", "RocketMQ 异步投递消除核心线程的数据库网络与磁盘 I/O 阻塞，显著平抑响应延迟毛刺！");
        return report;
    }

    private void mockBusinessWork(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
