package com.example.agent.customer;

import java.util.concurrent.*;
import static com.example.agent.customer.CustomerJson.error;

/** Nested calls inherit the earliest deadline. Waiting time and retries consume the same budget. */
public final class CustomerBudget {
    private static final ExecutorService EXEC = Executors.newVirtualThreadPerTaskExecutor();
    private static final Semaphore CAPACITY = new Semaphore(64);
    private static final ThreadLocal<Long> ACTIVE = new ThreadLocal<>();
    private final long deadline;

    public CustomerBudget(int ms) {
        if (ms <= 0) throw new IllegalArgumentException("Timeout must be positive");
        deadline = Math.min(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ms),
            ACTIVE.get() == null ? Long.MAX_VALUE : ACTIVE.get());
    }
    public int remaining() { return milliseconds(deadline); }
    private static int milliseconds(long deadline) {
        if (Thread.currentThread().isInterrupted()) throw error(504,"AGENT_CANCELLED","请求已取消");
        long left = TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime());
        if (left <= 0) throw error(504,"AGENT_TIMEOUT","处理超时，请稍后重试或联系人工");
        return (int)Math.min(left,Integer.MAX_VALUE);
    }
    public static int remaining(int maximum) {
        return ACTIVE.get() == null ? maximum : Math.min(maximum,milliseconds(ACTIVE.get()));
    }
    public <T> T call(int maxMs,Callable<T> work) {
        long end = Math.min(deadline, System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(remaining(maxMs)));
        milliseconds(end);
        if (!CAPACITY.tryAcquire()) throw error(503,"AGENT_BUSY","客服繁忙，请稍后重试");
        Future<T> future = EXEC.submit(() -> {
            ACTIVE.set(end);
            try { milliseconds(end); T value=work.call(); milliseconds(end); return value; }
            finally { ACTIVE.remove(); CAPACITY.release(); }
        });
        try { return future.get(milliseconds(end),TimeUnit.MILLISECONDS); }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();throw error(504,"AGENT_CANCELLED","请求已取消");
        } catch (TimeoutException e) { throw error(504,"AGENT_TIMEOUT","处理超时，请稍后重试或联系人工"); }
        catch (ExecutionException e) {
            if(e.getCause() instanceof RuntimeException r)throw r;
            throw error(503,"AGENT_DEPENDENCY_UNAVAILABLE","服务暂时不可用");
        } finally { if(!future.isDone())future.cancel(true); }
    }
}
