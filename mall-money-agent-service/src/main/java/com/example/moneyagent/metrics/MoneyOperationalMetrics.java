package com.example.moneyagent.metrics;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class MoneyOperationalMetrics {

    private final AtomicLong taskStartedCount = new AtomicLong(0);
    private final AtomicLong taskCompletedCount = new AtomicLong(0);
    private final AtomicLong taskFailedCount = new AtomicLong(0);
    private final AtomicLong idempotencyHitCount = new AtomicLong(0);
    private final AtomicLong toolCallCount = new AtomicLong(0);
    private final AtomicLong modelCallCount = new AtomicLong(0);
    private final AtomicLong inputRequestedCount = new AtomicLong(0);
    private final AtomicLong rpcFailureCount = new AtomicLong(0);
    private final AtomicLong replayCount = new AtomicLong(0);
    private final AtomicLong replaySuccessCount = new AtomicLong(0);

    private final ConcurrentHashMap<String, AtomicLong> byProblemFamily = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> byOutcome = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> byTool = new ConcurrentHashMap<>();

    public void recordTaskStarted(String problemFamily) {
        taskStartedCount.incrementAndGet();
        if (problemFamily != null) {
            byProblemFamily.computeIfAbsent(problemFamily, k -> new AtomicLong(0)).incrementAndGet();
        }
    }

    public void recordTaskCompleted(String problemFamily, String outcome, long durationMs) {
        taskCompletedCount.incrementAndGet();
        if (outcome != null) {
            byOutcome.computeIfAbsent(outcome, k -> new AtomicLong(0)).incrementAndGet();
        }
    }

    public void recordTaskFailed(String problemFamily, String errorCode, long durationMs) {
        taskFailedCount.incrementAndGet();
    }

    public void recordIdempotencyHit() {
        idempotencyHitCount.incrementAndGet();
    }

    public void recordToolCall() {
        toolCallCount.incrementAndGet();
    }

    public void recordToolCall(String toolName, boolean success, long durationMs) {
        toolCallCount.incrementAndGet();
        if (toolName != null) {
            byTool.computeIfAbsent(toolName + ":" + (success ? "OK" : "FAIL"), k -> new AtomicLong(0)).incrementAndGet();
        }
    }

    public void recordModelCall() {
        modelCallCount.incrementAndGet();
    }

    public void recordModelCall(String modelName, boolean success, long durationMs) {
        modelCallCount.incrementAndGet();
    }

    public void recordInputRequested(String problemFamily) {
        inputRequestedCount.incrementAndGet();
    }

    public void recordRpcFailure(String targetService, String method) {
        rpcFailureCount.incrementAndGet();
    }

    public void recordReplayExecuted(boolean success) {
        replayCount.incrementAndGet();
        if (success) {
            replaySuccessCount.incrementAndGet();
        }
    }

    public long getTaskStartedCount() {
        return taskStartedCount.get();
    }

    public long getTaskCompletedCount() {
        return taskCompletedCount.get();
    }

    public long getTaskFailedCount() {
        return taskFailedCount.get();
    }

    public long getIdempotencyHitCount() {
        return idempotencyHitCount.get();
    }

    public long getToolCallCount() {
        return toolCallCount.get();
    }

    public long getModelCallCount() {
        return modelCallCount.get();
    }

    public long getReplayCount() {
        return replayCount.get();
    }

    public Map<String, Object> getMetricsSnapshot() {
        Map<String, Object> map = new ConcurrentHashMap<>();
        map.put("taskStarted", taskStartedCount.get());
        map.put("taskCompleted", taskCompletedCount.get());
        map.put("taskFailed", taskFailedCount.get());
        map.put("idempotencyHit", idempotencyHitCount.get());
        map.put("toolCall", toolCallCount.get());
        map.put("modelCall", modelCallCount.get());
        map.put("inputRequested", inputRequestedCount.get());
        map.put("rpcFailure", rpcFailureCount.get());
        map.put("replay", replayCount.get());
        map.put("replaySuccess", replaySuccessCount.get());
        return map;
    }
}

