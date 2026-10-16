package com.example.agent.customer.memory;

import java.util.concurrent.TimeUnit;
import static com.example.agent.customer.CustomerJson.error;

public final class MemoryDeadline {
    private final long deadlineNanos;

    public MemoryDeadline(int timeoutMs) {
        if (timeoutMs <= 0) throw new IllegalArgumentException("Timeout must be positive");
        this.deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
    }

    public static MemoryDeadline ofRemaining(long deadlineNanos) {
        return new MemoryDeadline(deadlineNanos, true);
    }

    private MemoryDeadline(long deadlineNanos, boolean direct) {
        this.deadlineNanos = deadlineNanos;
    }

    public int remainingMs() {
        if (Thread.currentThread().isInterrupted()) {
            throw error(504, "AGENT_CANCELLED", "请求已取消");
        }
        long left = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
        if (left <= 0) {
            throw error(504, "AGENT_TIMEOUT", "记忆操作超时");
        }
        return (int) Math.min(left, Integer.MAX_VALUE);
    }

    public boolean isExpired() {
        return System.nanoTime() >= deadlineNanos;
    }

    public long getDeadlineNanos() {
        return deadlineNanos;
    }
}
