package com.example.risk.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.risk.dto.MerchantMetricAggregate;
import com.example.risk.dto.UserMetricAggregate;
import com.example.risk.entity.RiskEvent;
import com.example.risk.entity.RiskIndicator;
import com.example.risk.entity.RiskIndicatorRefreshTask;
import com.example.risk.entity.UserDevice;
import com.example.risk.entity.UserIp;
import com.example.risk.mapper.RiskEventMapper;
import com.example.risk.mapper.RiskIndicatorMapper;
import com.example.risk.mapper.RiskIndicatorRefreshTaskMapper;
import com.example.risk.mapper.UserDeviceMapper;
import com.example.risk.mapper.UserIpMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@Slf4j
public class RiskIndicatorService {
    private static final LocalDateTime TOTAL_WINDOW_START = LocalDateTime.of(1970, 1, 1, 0, 0);
    private static final Map<String, Duration> FRESHNESS = Map.of(
            "10M", Duration.ofSeconds(10),
            "24H", Duration.ofSeconds(60),
            "7D", Duration.ofMinutes(5),
            "30D", Duration.ofMinutes(10),
            "TOTAL", Duration.ofMinutes(5));

    private final RiskIndicatorMapper indicatorMapper;
    private final RiskIndicatorRefreshTaskMapper taskMapper;
    private final RiskEventMapper eventMapper;
    private final UserDeviceMapper deviceMapper;
    private final UserIpMapper ipMapper;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public RiskIndicatorService(RiskIndicatorMapper indicatorMapper,
                                RiskIndicatorRefreshTaskMapper taskMapper,
                                RiskEventMapper eventMapper,
                                UserDeviceMapper deviceMapper,
                                UserIpMapper ipMapper,
                                ObjectMapper objectMapper,
                                MeterRegistry meterRegistry) {
        this.indicatorMapper = indicatorMapper;
        this.taskMapper = taskMapper;
        this.eventMapper = eventMapper;
        this.deviceMapper = deviceMapper;
        this.ipMapper = ipMapper;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    public IndicatorSnapshot metricsFor(String subjectType, Long subjectId, Set<String> expectedMetrics,
                                        RiskQueryTracker tracker) {
        if (subjectType == null || subjectType.isBlank() || subjectId == null) {
            return new IndicatorSnapshot(Map.of(), Set.of(), false);
        }
        return metricsForUserAndMerchant("USER".equals(subjectType) ? subjectId : null,
                "MERCHANT".equals(subjectType) ? subjectId : null,
                "USER".equals(subjectType) ? expectedMetrics : Set.of(),
                "MERCHANT".equals(subjectType) ? expectedMetrics : Set.of(), tracker)
                .getOrDefault(subjectType, new IndicatorSnapshot(Map.of(), Set.of(), false));
    }

    public Map<String, IndicatorSnapshot> metricsForUserAndMerchant(Long userId, Long merchantId,
                                                                    Set<String> userMetrics,
                                                                    Set<String> merchantMetrics,
                                                                    RiskQueryTracker tracker) {
        Map<String, IndicatorSnapshot> result = new LinkedHashMap<>();
        if (userId == null && merchantId == null) {
            return result;
        }
        LambdaQueryWrapper<RiskIndicator> wrapper = new LambdaQueryWrapper<>();
        if (userId != null && merchantId != null) {
            wrapper.and(nested -> nested.eq(RiskIndicator::getSubjectType, "USER")
                            .eq(RiskIndicator::getSubjectId, userId))
                    .or(nested -> nested.eq(RiskIndicator::getSubjectType, "MERCHANT")
                            .eq(RiskIndicator::getSubjectId, merchantId));
        } else if (userId != null) {
            wrapper.eq(RiskIndicator::getSubjectType, "USER").eq(RiskIndicator::getSubjectId, userId);
        } else {
            wrapper.eq(RiskIndicator::getSubjectType, "MERCHANT").eq(RiskIndicator::getSubjectId, merchantId);
        }
        List<RiskIndicator> indicators = indicatorMapper.selectList(wrapper);
        tracker.increment();
        if (userId != null) {
            result.put("USER", snapshotForSubject("USER", userId, userMetrics,
                    indicators.stream().filter(indicator -> "USER".equals(indicator.getSubjectType())).toList(),
                    tracker));
        }
        if (merchantId != null) {
            result.put("MERCHANT", snapshotForSubject("MERCHANT", merchantId, merchantMetrics,
                    indicators.stream().filter(indicator -> "MERCHANT".equals(indicator.getSubjectType())).toList(),
                    tracker));
        }
        return result;
    }

    public void enqueueEvent(RiskEvent event) {
        if (event == null) {
            return;
        }
        if (event.getUserId() != null) {
            enqueueRefresh("USER", event.getUserId(), null);
        }
        if (event.getMerchantId() != null) {
            enqueueRefresh("MERCHANT", event.getMerchantId(), null);
        }
    }

    public void enqueueRefresh(String subjectType, Long subjectId, Set<String> metricCodes) {
        taskMapper.enqueue(subjectType, subjectId, writeMetricCodes(metricCodes), LocalDateTime.now());
    }

    public int refreshDueTasks(int limit) {
        long startTime = System.nanoTime();
        try {
            List<RiskIndicatorRefreshTask> tasks = taskMapper.selectDue(LocalDateTime.now(), limit);
            int processed = 0;
            for (RiskIndicatorRefreshTask task : tasks) {
                if (taskMapper.markRunning(task.getId(), LocalDateTime.now()) <= 0) {
                    continue;
                }
                try {
                    List<RiskIndicator> indicators = calculateIndicators(task.getSubjectType(),
                            task.getSubjectId(), new RiskQueryTracker());
                    upsertAll(indicators);
                    taskMapper.clearOldStatus(task.getSubjectType(), task.getSubjectId(), "SUCCESS", task.getId());
                    if (taskMapper.markSuccess(task.getId(), LocalDateTime.now()) > 0) {
                        processed++;
                    }
                } catch (Exception e) {
                    markFailure(task, e);
                }
            }
            return processed;
        } finally {
            meterRegistry.timer("risk_indicator_refresh_duration_seconds")
                    .record(Duration.ofNanos(System.nanoTime() - startTime));
        }
    }

    private List<RiskIndicator> calculateIndicators(String subjectType, Long subjectId,
                                                    RiskQueryTracker tracker) {
        LocalDateTime now = LocalDateTime.now();
        if ("USER".equals(subjectType)) {
            UserMetricAggregate aggregate = eventMapper.aggregateUserMetrics(subjectId,
                    now.minusMinutes(10), now.minusHours(24), now.minusDays(7));
            tracker.increment();
            Long deviceCount = deviceMapper.selectCount(new LambdaQueryWrapper<UserDevice>()
                    .eq(UserDevice::getUserId, subjectId)
                    .ge(UserDevice::getLastSeenTime, now.minusDays(30)));
            Long ipCount = ipMapper.selectCount(new LambdaQueryWrapper<UserIp>()
                    .eq(UserIp::getUserId, subjectId)
                    .ge(UserIp::getLastSeenTime, now.minusDays(30)));
            tracker.increment(2);
            return List.of(
                    indicator("USER_ORDER_COUNT_10M", subjectType, subjectId, "10M",
                            now.minusMinutes(10), BigDecimal.valueOf(aggregate.getOrderCount10m())),
                    indicator("USER_ORDER_COUNT_24H", subjectType, subjectId, "24H",
                            now.minusHours(24), BigDecimal.valueOf(aggregate.getOrderCount24h())),
                    indicator("USER_COMPLETED_COUNT", subjectType, subjectId, "TOTAL",
                            TOTAL_WINDOW_START, BigDecimal.valueOf(aggregate.getCompletedCount())),
                    indicator("USER_DISPUTE_COUNT_7D", subjectType, subjectId, "7D",
                            now.minusDays(7), BigDecimal.valueOf(aggregate.getDisputeCount7d())),
                    indicator("USER_DEVICE_COUNT_30D", subjectType, subjectId, "30D",
                            now.minusDays(30), BigDecimal.valueOf(deviceCount)),
                    indicator("USER_IP_COUNT_30D", subjectType, subjectId, "30D",
                            now.minusDays(30), BigDecimal.valueOf(ipCount)),
                    indicator("LOGIN_FAIL_COUNT_10M", subjectType, subjectId, "10M",
                            now.minusMinutes(10), BigDecimal.valueOf(aggregate.getLoginFailCount10m())));
        }
        if ("MERCHANT".equals(subjectType)) {
            MerchantMetricAggregate aggregate = eventMapper.aggregateMerchantMetrics(subjectId,
                    now.minusDays(30), now.minusHours(24));
            tracker.increment();
            long completed = aggregate.getCompletedCount() == null ? 0 : aggregate.getCompletedCount();
            BigDecimal refundRate = completed == 0 ? BigDecimal.ZERO
                    : BigDecimal.valueOf(aggregate.getRefundCount30d())
                    .divide(BigDecimal.valueOf(completed), 4, RoundingMode.HALF_UP);
            BigDecimal disputeRate = completed == 0 ? BigDecimal.ZERO
                    : BigDecimal.valueOf(aggregate.getDisputeCount30d())
                    .divide(BigDecimal.valueOf(completed), 4, RoundingMode.HALF_UP);
            return List.of(
                    indicator("MERCHANT_COMPLETED_COUNT", subjectType, subjectId, "TOTAL",
                            TOTAL_WINDOW_START, BigDecimal.valueOf(completed)),
                    indicator("MERCHANT_REFUND_COUNT_30D", subjectType, subjectId, "30D",
                            now.minusDays(30), BigDecimal.valueOf(aggregate.getRefundCount30d())),
                    indicator("MERCHANT_DISPUTE_COUNT_30D", subjectType, subjectId, "30D",
                            now.minusDays(30), BigDecimal.valueOf(aggregate.getDisputeCount30d())),
                    indicator("MERCHANT_REFUND_RATE_30D", subjectType, subjectId, "30D",
                            now.minusDays(30), refundRate),
                    indicator("MERCHANT_DISPUTE_RATE_30D", subjectType, subjectId, "30D",
                            now.minusDays(30), disputeRate),
                    indicator("MERCHANT_WITHDRAW_AMOUNT_24H", subjectType, subjectId, "24H",
                            now.minusHours(24), aggregate.getWithdrawAmount24h()));
        }
        return List.of();
    }

    private void upsertAll(List<RiskIndicator> indicators) {
        for (RiskIndicator indicator : indicators) {
            indicatorMapper.upsert(indicator);
        }
    }

    private IndicatorSnapshot snapshotForSubject(String subjectType, Long subjectId, Set<String> expectedMetrics,
                                                 List<RiskIndicator> indicators, RiskQueryTracker tracker) {
        if (indicators.isEmpty()) {
            List<RiskIndicator> computed = calculateIndicators(subjectType, subjectId, tracker);
            upsertAll(computed);
            Map<String, Object> values = valuesOf(computed);
            return new IndicatorSnapshot(values, missing(expectedMetrics, values), true);
        }
        Map<String, Object> values = valuesOf(indicators);
        Set<String> missing = missing(expectedMetrics, values);
        LocalDateTime now = LocalDateTime.now();
        recordRefreshLag(indicators, now);
        boolean stale = indicators.stream().anyMatch(indicator -> isStale(indicator, now));
        if (stale || !missing.isEmpty()) {
            enqueueRefresh(subjectType, subjectId, expectedMetrics);
        }
        return new IndicatorSnapshot(values, missing, !stale);
    }

    private void recordRefreshLag(List<RiskIndicator> indicators, LocalDateTime now) {
        Duration maxLag = Duration.ZERO;
        for (RiskIndicator indicator : indicators) {
            if (indicator.getUpdatedTime() == null) {
                continue;
            }
            Duration lag = Duration.between(indicator.getUpdatedTime(), now);
            if (lag.compareTo(maxLag) > 0) {
                maxLag = lag;
            }
        }
        meterRegistry.timer("risk_indicator_refresh_lag_seconds").record(maxLag);
    }

    private RiskIndicator indicator(String metricCode, String subjectType, Long subjectId,
                                    String windowType, LocalDateTime windowStart, BigDecimal value) {
        RiskIndicator indicator = new RiskIndicator();
        indicator.setMetricCode(metricCode);
        indicator.setSubjectType(subjectType);
        indicator.setSubjectId(subjectId);
        indicator.setWindowType(windowType);
        indicator.setWindowStart(windowStart);
        indicator.setWindowEnd(LocalDateTime.now());
        indicator.setMetricValue(value == null ? BigDecimal.ZERO.setScale(4)
                : value.setScale(4, RoundingMode.HALF_UP));
        indicator.setUpdatedTime(LocalDateTime.now());
        return indicator;
    }

    private Map<String, Object> valuesOf(List<RiskIndicator> indicators) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (RiskIndicator indicator : indicators) {
            values.put(indicator.getMetricCode(), indicator.getMetricValue());
        }
        return values;
    }

    private Set<String> missing(Set<String> expected, Map<String, Object> values) {
        if (expected == null || expected.isEmpty()) {
            return Set.of();
        }
        return expected.stream()
                .filter(code -> !values.containsKey(code))
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    private boolean isStale(RiskIndicator indicator, LocalDateTime now) {
        if (indicator.getUpdatedTime() == null) {
            return true;
        }
        Duration allowed = FRESHNESS.getOrDefault(indicator.getWindowType(), Duration.ofSeconds(60));
        return Duration.between(indicator.getUpdatedTime(), now).compareTo(allowed) > 0;
    }

    private void markFailure(RiskIndicatorRefreshTask task, Exception error) {
        int retryCount = (task.getRetryCount() == null ? 0 : task.getRetryCount()) + 1;
        int maxRetryCount = task.getMaxRetryCount() == null ? 3 : task.getMaxRetryCount();
        String status = retryCount >= maxRetryCount ? "MANUAL_PENDING" : "FAILED";
        LocalDateTime now = LocalDateTime.now();
        taskMapper.clearOldStatus(task.getSubjectType(), task.getSubjectId(), status, task.getId());
        taskMapper.markResult(task.getId(), status, retryCount,
                now.plusSeconds(Math.min(300, 30L * retryCount)), safeError(error), now);
        log.warn("风控指标刷新失败, taskId={}, subjectType={}, subjectId={}, status={}, error={}",
                task.getId(), task.getSubjectType(), task.getSubjectId(), status, safeError(error));
    }

    private String safeError(Exception error) {
        String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        return message.length() > 500 ? message.substring(0, 500) : message;
    }

    private String writeMetricCodes(Set<String> metricCodes) {
        if (metricCodes == null || metricCodes.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(metricCodes);
        } catch (Exception e) {
            return null;
        }
    }

    public record IndicatorSnapshot(Map<String, Object> values, Set<String> missingMetrics, boolean fresh) {
    }
}
