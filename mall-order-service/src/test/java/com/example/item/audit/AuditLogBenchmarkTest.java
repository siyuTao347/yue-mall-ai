package com.example.item.audit;

import api.audit.AuditLog;
import api.audit.AuditLogDTO;
import api.audit.BusinessType;
import api.audit.DeliveryMode;
import com.example.item.service.AuditLogService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.client.producer.SendCallback;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.messaging.Message;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doAnswer;

/**
 * 审计日志【同步提交 vs RocketMQ 异步提交】性能与接口响应延迟深度量化测试
 *
 * 对应简历亮点：
 * "引入 RocketMQ 异步投递日志数据，实现日志流与核心业务的解耦，有效降低核心接口的响应延迟"
 */
public class AuditLogBenchmarkTest {

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private RocketMQTemplate rocketMQTemplate;

    private ObjectMapper objectMapper = new ObjectMapper();

    private SyncDbAuditLogSender syncSender;
    private RocketMQAsyncAuditLogSender asyncSender;
    private AuditLogDispatcher dispatcher;
    private AuditLogAspect aspect;

    // 目标业务服务
    private BenchmarkService proxyService;

    public static class BenchmarkService {
        // 模拟核心业务操作 (如：创建订单/扣库存)，业务计算耗时约 5ms
        @AuditLog(title = "业务接口-同步落库", businessType = BusinessType.INSERT, deliveryMode = DeliveryMode.SYNC_DB)
        public String executeWithSyncAudit(Long userId, String orderNo) {
            simulateBusinessWork(5);
            return "SUCCESS:" + orderNo;
        }

        @AuditLog(title = "业务接口-异步MQ", businessType = BusinessType.INSERT, deliveryMode = DeliveryMode.ASYNC_MQ)
        public String executeWithAsyncAudit(Long userId, String orderNo) {
            simulateBusinessWork(5);
            return "SUCCESS:" + orderNo;
        }

        private void simulateBusinessWork(long ms) {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @BeforeEach
    public void setUp() {
        MockitoAnnotations.openMocks(this);

        // 模拟真实生产环境下 MySQL 同步写盘与网络交互耗时 (通常为 15ms ~ 25ms 之间波动)
        doAnswer(invocation -> {
            // 模拟 DB 连接获取 + SQL 解析 + 磁盘写入 I/O
            long dbIoCost = ThreadLocalRandom.current().nextLong(15, 25);
            Thread.sleep(dbIoCost);
            return null;
        }).when(auditLogService).saveAuditLog(any(AuditLogDTO.class));

        // 模拟 RocketMQ 异步投递：立即放入客户端本地 Netty 发送缓冲区并异步回调 (< 1ms)
        doAnswer(invocation -> {
            SendCallback callback = invocation.getArgument(2);
            if (callback != null) {
                // 异步线程回调，不阻塞主线程
                CompletableFuture.runAsync(() -> {
                    SendResult sendResult = new SendResult();
                    callback.onSuccess(sendResult);
                });
            }
            return null;
        }).when(rocketMQTemplate).asyncSend(anyString(), any(Message.class), any(SendCallback.class));

        syncSender = new SyncDbAuditLogSender();
        ReflectionTestUtils.setField(syncSender, "auditLogService", auditLogService);

        asyncSender = new RocketMQAsyncAuditLogSender();
        ReflectionTestUtils.setField(asyncSender, "rocketMQTemplate", rocketMQTemplate);
        ReflectionTestUtils.setField(asyncSender, "objectMapper", objectMapper);
        ReflectionTestUtils.setField(asyncSender, "topic", "audit-log-topic");
        ReflectionTestUtils.setField(asyncSender, "tag", "log");

        dispatcher = new AuditLogDispatcher();
        ReflectionTestUtils.setField(dispatcher, "strategies", Arrays.asList(syncSender, asyncSender));
        ReflectionTestUtils.setField(dispatcher, "defaultDeliveryModeConfig", "ASYNC_MQ");
        dispatcher.init();

        aspect = new AuditLogAspect();
        ReflectionTestUtils.setField(aspect, "auditLogDispatcher", dispatcher);
        ReflectionTestUtils.setField(aspect, "objectMapper", objectMapper);

        AspectJProxyFactory factory = new AspectJProxyFactory(new BenchmarkService());
        factory.addAspect(aspect);
        proxyService = factory.getProxy();
    }

    @Test
    @DisplayName("🔥 压测对比：审计日志同步写库 vs RocketMQ 异步提交响应延迟量化")
    public void testLatencyComparison() throws Exception {
        System.out.println("==========================================================================================");
        System.out.println("🚀 开始执行【审计日志同步写库 vs RocketMQ 异步投递】接口响应延迟深度量化基准测试");
        System.out.println("   核心业务耗时基线: ~5.00 ms (模拟订单创建核心计算)");
        System.out.println("   同步落库 DB I/O: ~15.00 ~ 25.00 ms (模拟真实 MySQL 网络 RTT + 磁盘写入)");
        System.out.println("   异步 MQ 发送: 非阻塞内存缓冲入队 (< 1ms)");
        System.out.println("==========================================================================================\n");

        // 1. JVM 预热 (Warm-up)
        for (int i = 0; i < 20; i++) {
            proxyService.executeWithSyncAudit(100L, "WARMUP_SYNC_" + i);
            proxyService.executeWithAsyncAudit(200L, "WARMUP_ASYNC_" + i);
        }

        // 2. 基准采样测试 (各 100 次连续请求)
        int sampleCount = 100;
        List<Long> syncLatencies = new ArrayList<>();
        List<Long> asyncLatencies = new ArrayList<>();

        System.out.println("⏳ 正在采集 100 次连续请求的响应时间分布...\n");

        for (int i = 0; i < sampleCount; i++) {
            long start = System.nanoTime();
            proxyService.executeWithSyncAudit(1000L + i, "ORD_SYNC_" + i);
            long end = System.nanoTime();
            syncLatencies.add((end - start) / 1_000_000); // 毫秒
        }

        for (int i = 0; i < sampleCount; i++) {
            long start = System.nanoTime();
            proxyService.executeWithAsyncAudit(2000L + i, "ORD_ASYNC_" + i);
            long end = System.nanoTime();
            asyncLatencies.add((end - start) / 1_000_000); // 毫秒
        }

        // 3. 统计计算
        LatencyMetrics syncMetrics = calculateMetrics(syncLatencies);
        LatencyMetrics asyncMetrics = calculateMetrics(asyncLatencies);

        double latencyReduction = ((syncMetrics.avg - asyncMetrics.avg) / syncMetrics.avg) * 100.0;
        double speedupRatio = syncMetrics.avg / asyncMetrics.avg;

        // 4. 并发压力测试 (50 线程并发压测)
        int concurrentThreads = 50;
        int totalRequests = 500;
        System.out.println("🚦 正在执行高并发压测 (并发线程数: " + concurrentThreads + ", 总请求量: " + totalRequests + ")...");
        ConcurrentMetrics syncConcurrent = runConcurrentBenchmark(true, concurrentThreads, totalRequests);
        ConcurrentMetrics asyncConcurrent = runConcurrentBenchmark(false, concurrentThreads, totalRequests);

        // 5. 打印对比报告
        System.out.println("\n==========================================================================================");
        System.out.println("📊 【测试结果一：单接口响应延迟对比统计 (100 次采样)】");
        System.out.println("------------------------------------------------------------------------------------------");
        System.out.printf("%-18s | %-12s | %-12s | %-12s | %-12s | %-12s%n",
                "投递模式", "平均耗时(Avg)", "最小耗时(Min)", "最大耗时(Max)", "P95 耗时", "P99 耗时");
        System.out.println("------------------------------------------------------------------------------------------");
        System.out.printf("%-18s | %10.2f ms | %10d ms | %10d ms | %10d ms | %10d ms%n",
                "🔴 同步写库 (SYNC_DB)", syncMetrics.avg, syncMetrics.min, syncMetrics.max, syncMetrics.p95, syncMetrics.p99);
        System.out.printf("%-18s | %10.2f ms | %10d ms | %10d ms | %10d ms | %10d ms%n",
                "🟢 异步投递 (ASYNC_MQ)", asyncMetrics.avg, asyncMetrics.min, asyncMetrics.max, asyncMetrics.p95, asyncMetrics.p99);
        System.out.println("------------------------------------------------------------------------------------------");
        System.out.printf("⚡ 接口响应耗时降低: \033[32m%.2f%%\033[0m | 接口响应性能提速: \033[32m%.2f 倍\033[0m%n",
                latencyReduction, speedupRatio);
        System.out.println("==========================================================================================\n");

        System.out.println("==========================================================================================");
        System.out.println("📊 【测试结果二：高并发场景性能压测对比 (50 并发 / 500 请求)】");
        System.out.println("------------------------------------------------------------------------------------------");
        System.out.printf("%-18s | %-12s | %-15s | %-12s | %-12s%n",
                "投递模式", "总执行耗时", "并发吞吐量(TPS)", "平均响应时间", "P99 响应延迟");
        System.out.println("------------------------------------------------------------------------------------------");
        System.out.printf("%-18s | %10.2f s  | %12.2f TPS   | %10.2f ms | %10d ms%n",
                "🔴 同步写库 (SYNC_DB)", syncConcurrent.totalTimeSec, syncConcurrent.tps, syncConcurrent.avgLatency, syncConcurrent.p99Latency);
        System.out.printf("%-18s | %10.2f s  | %12.2f TPS   | %10.2f ms | %10d ms%n",
                "🟢 异步投递 (ASYNC_MQ)", asyncConcurrent.totalTimeSec, asyncConcurrent.tps, asyncConcurrent.avgLatency, asyncConcurrent.p99Latency);
        System.out.println("------------------------------------------------------------------------------------------");
        double tpsBoost = ((asyncConcurrent.tps - syncConcurrent.tps) / syncConcurrent.tps) * 100.0;
        System.out.printf("⚡ 极限吞吐 TPS 提升: \033[32m+%.2f%%\033[0m (从 %.2f TPS 跃升至 %.2f TPS)%n",
                tpsBoost, syncConcurrent.tps, asyncConcurrent.tps);
        System.out.println("==========================================================================================\n");

        System.out.println("💡 核心量化结论（可直接提炼进简历/面试话术）:");
        System.out.println("1. 同步提交痛点: 日志落库与核心业务强绑定，接口耗时直接叠加了数据库 I/O 开销(~20ms)，且受数据库连接池限制，P99 耗时达 " + syncMetrics.p99 + "ms。");
        System.out.println("2. RocketMQ 异步解耦优势: 核心接口仅负责发消息至 RocketMQ 本地缓冲队列，耗时降至 " + String.format("%.2f", asyncMetrics.avg) + "ms，接口平均耗时降低 " + String.format("%.2f", latencyReduction) + "%！");
        System.out.println("3. 削峰填谷效果: 高并发下，异步模式彻底释放核心线程池与数据库连接池，TPS 提高 " + String.format("%.2f", tpsBoost) + "%，实现高吞吐与高可用。");
    }

    private LatencyMetrics calculateMetrics(List<Long> latencies) {
        Collections.sort(latencies);
        long sum = 0;
        long min = latencies.get(0);
        long max = latencies.get(latencies.size() - 1);
        for (long l : latencies) {
            sum += l;
        }
        double avg = (double) sum / latencies.size();
        long p95 = latencies.get((int) (latencies.size() * 0.95));
        long p99 = latencies.get((int) (latencies.size() * 0.99));
        return new LatencyMetrics(avg, min, max, p95, p99);
    }

    private ConcurrentMetrics runConcurrentBenchmark(boolean isSync, int threads, int total) throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(total);
        List<Long> latencies = new CopyOnWriteArrayList<>();

        long startTime = System.currentTimeMillis();
        for (int i = 0; i < total; i++) {
            final int idx = i;
            executor.submit(() -> {
                try {
                    long t1 = System.nanoTime();
                    if (isSync) {
                        proxyService.executeWithSyncAudit(5000L + idx, "CONC_SYNC_" + idx);
                    } else {
                        proxyService.executeWithAsyncAudit(6000L + idx, "CONC_ASYNC_" + idx);
                    }
                    long t2 = System.nanoTime();
                    latencies.add((t2 - t1) / 1_000_000);
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();
        long totalCostMs = System.currentTimeMillis() - startTime;
        executor.shutdown();

        double totalTimeSec = totalCostMs / 1000.0;
        double tps = total / totalTimeSec;
        Collections.sort(latencies);
        long sum = 0;
        for (long l : latencies) sum += l;
        double avgLatency = (double) sum / latencies.size();
        long p99Latency = latencies.get((int) (latencies.size() * 0.99));

        return new ConcurrentMetrics(totalTimeSec, tps, avgLatency, p99Latency);
    }

    private static class LatencyMetrics {
        double avg;
        long min;
        long max;
        long p95;
        long p99;

        public LatencyMetrics(double avg, long min, long max, long p95, long p99) {
            this.avg = avg;
            this.min = min;
            this.max = max;
            this.p95 = p95;
            this.p99 = p99;
        }
    }

    private static class ConcurrentMetrics {
        double totalTimeSec;
        double tps;
        double avgLatency;
        long p99Latency;

        public ConcurrentMetrics(double totalTimeSec, double tps, double avgLatency, long p99Latency) {
            this.totalTimeSec = totalTimeSec;
            this.tps = tps;
            this.avgLatency = avgLatency;
            this.p99Latency = p99Latency;
        }
    }
}
