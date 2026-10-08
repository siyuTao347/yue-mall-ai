package com.example.risk.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.time.Duration;

@Slf4j
@Component
@Intercepts(@Signature(type = StatementHandler.class, method = "prepare",
        args = {Connection.class, Integer.class}))
public class SlowSqlInterceptor implements Interceptor {
    private final MeterRegistry meterRegistry;

    @Value("${performance.slow-sql-threshold-ms:500}")
    private long slowSqlThresholdMs;

    public SlowSqlInterceptor(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        StatementHandler handler = (StatementHandler) invocation.getTarget();
        String sql = handler.getBoundSql().getSql();
        long startTime = System.nanoTime();
        try {
            return invocation.proceed();
        } finally {
            Duration duration = Duration.ofNanos(System.nanoTime() - startTime);
            Timer.builder("database.sql.duration.seconds").register(meterRegistry).record(duration);
            if (duration.toMillis() >= slowSqlThresholdMs) {
                meterRegistry.counter("database.slow.sql.total").increment();
                log.warn("慢SQL, durationMs={}, thresholdMs={}, sql={}",
                        duration.toMillis(), slowSqlThresholdMs, sql);
            }
        }
    }
}
