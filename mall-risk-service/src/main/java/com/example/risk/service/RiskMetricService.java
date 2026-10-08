package com.example.risk.service;

import api.risk.RiskEvaluateRequest;
import api.risk.SensitiveWordHitDTO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.risk.dto.MerchantMetricAggregate;
import com.example.risk.dto.UserMetricAggregate;
import com.example.risk.entity.UserDevice;
import com.example.risk.entity.UserIp;
import com.example.risk.mapper.RiskEventMapper;
import com.example.risk.mapper.UserDeviceMapper;
import com.example.risk.mapper.UserIpMapper;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

@Service
@Slf4j
public class RiskMetricService {
    private static final Set<String> USER_METRICS = Set.of(
            "USER_ORDER_COUNT_10M", "USER_ORDER_COUNT_24H", "USER_COMPLETED_COUNT",
            "USER_DISPUTE_COUNT_7D", "USER_DEVICE_COUNT_30D", "USER_IP_COUNT_30D",
            "LOGIN_FAIL_COUNT_10M");
    private static final Set<String> MERCHANT_METRICS = Set.of(
            "MERCHANT_COMPLETED_COUNT", "MERCHANT_REFUND_COUNT_30D", "MERCHANT_DISPUTE_COUNT_30D",
            "MERCHANT_REFUND_RATE_30D", "MERCHANT_DISPUTE_RATE_30D", "MERCHANT_WITHDRAW_AMOUNT_24H");

    private final RiskEventMapper eventMapper;
    private final UserDeviceMapper deviceMapper;
    private final UserIpMapper ipMapper;
    private final RiskIdentityService identityService;
    private final RiskIndicatorService indicatorService;
    private final MeterRegistry meterRegistry;

    @Value("${risk.indicator.materialized-enabled:false}")
    private boolean materializedEnabled;
    @Value("${risk.indicator.query-budget:3}")
    private int queryBudget;

    public RiskMetricService(RiskEventMapper eventMapper, UserDeviceMapper deviceMapper,
                             UserIpMapper ipMapper, RiskIdentityService identityService,
                             RiskIndicatorService indicatorService, MeterRegistry meterRegistry) {
        this.eventMapper = eventMapper;
        this.deviceMapper = deviceMapper;
        this.ipMapper = ipMapper;
        this.identityService = identityService;
        this.indicatorService = indicatorService;
        this.meterRegistry = meterRegistry;
    }

    public Metrics calculate(RiskEvaluateRequest request) {
        long startTime = System.nanoTime();
        RiskQueryTracker tracker = new RiskQueryTracker();
        Map<String, Object> values = new LinkedHashMap<>();
        Set<String> missing = new LinkedHashSet<>();
        LocalDateTime now = LocalDateTime.now();
        Map<String, Object> payload = request.getPayload() == null ? Map.of() : request.getPayload();

        putNumber(values, "CURRENT_AMOUNT", request.getAmount());
        putNumber(values, "USER_AGE_HOURS", number(payload.get("userAgeHours")));
        putNumber(values, "MERCHANT_AGE_HOURS", number(payload.get("merchantAgeHours")));
        putNumber(values, "WITHDRAW_AMOUNT", request.getAmount());
        putNumber(values, "ORDER_PAY_TO_DELIVER_SECONDS", number(payload.get("payToDeliverSeconds")));
        putNumber(values, "ORDER_VIEW_TO_CONFIRM_SECONDS", number(payload.get("viewToConfirmSeconds")));
        putNumber(values, "MERCHANT_SETTLE_TO_WITHDRAW_MINUTES", number(payload.get("settleToWithdrawMinutes")));
        putText(values, "ORDER_RISK_STATUS", text(payload.get("orderRiskStatus")));

        if (materializedEnabled) {
            Map<String, RiskIndicatorService.IndicatorSnapshot> snapshots =
                    indicatorService.metricsForUserAndMerchant(request.getUserId(), request.getMerchantId(),
                            USER_METRICS, MERCHANT_METRICS, tracker);
            applyUserSnapshot(values, missing, snapshots.get("USER"), request);
            applyMerchantSnapshot(values, missing, snapshots.get("MERCHANT"), request);
            recordCacheMetrics(snapshots);
        } else {
            calculateLiveUserMetrics(values, request, tracker, now);
            calculateLiveMerchantMetrics(values, request, tracker, now);
        }

        if ("REGISTER".equals(request.getScene())) {
            putNumber(values, "REGISTER_SAME_IP_COUNT_10M", request.getIpHash() == null ? null
                    : BigDecimal.valueOf(eventMapper.countIpEvents("REGISTER", request.getIpHash(),
                    now.minusMinutes(10)) + 1));
            tracker.increment();
            putNumber(values, "REGISTER_SAME_DEVICE_COUNT_30D", request.getDeviceHash() == null ? null
                    : BigDecimal.valueOf(eventMapper.countDeviceEvents("REGISTER", request.getDeviceHash(),
                    now.minusDays(30)) + 1));
            tracker.increment();
        }

        String accountHash = text(payload.get("withdrawAccountHash"));
        if (accountHash != null) {
            values.put("WITHDRAW_ACCOUNT_MERCHANT_COUNT", eventMapper.countWithdrawAccountMerchants(
                    accountHash, now.minusDays(180)) + 1);
            tracker.increment();
        }

        BigDecimal median = number(payload.get("categoryMedianPrice"));
        if (median != null && median.compareTo(BigDecimal.ZERO) > 0 && request.getAmount() != null) {
            values.put("ITEM_PRICE_DEVIATION", request.getAmount()
                    .divide(median, 4, RoundingMode.HALF_UP)
                    .subtract(BigDecimal.ONE));
        }
        values.put("BUYER_SELLER_RELATION", identityService.buyerSellerRelation(request, tracker));
        if (request.getSensitiveHits() != null && !request.getSensitiveHits().isEmpty()) {
            values.put("SENSITIVE_WORD_CATEGORY", request.getSensitiveHits().stream()
                    .map(SensitiveWordHitDTO::getCategory)
                    .findFirst().orElse(null));
        }

        long durationMs = Duration.ofNanos(System.nanoTime() - startTime).toMillis();
        Timer.builder("risk_evaluate_duration_seconds").register(meterRegistry)
                .record(Duration.ofMillis(durationMs));
        DistributionSummary.builder("risk_evaluate_db_query_count").register(meterRegistry)
                .record(tracker.count());
        if (tracker.count() > queryBudget) {
            log.warn("风控评估查询超过预算, eventNo={}, scene={}, queryCount={}, budget={}, missingMetrics={}",
                    request.getEventNo(), request.getScene(), tracker.count(), queryBudget, missing);
        }
        return new Metrics(values, missing, tracker.count(), durationMs);
    }

    private void calculateLiveUserMetrics(Map<String, Object> values, RiskEvaluateRequest request,
                                          RiskQueryTracker tracker, LocalDateTime now) {
        if (request.getUserId() == null) {
            return;
        }
        UserMetricAggregate aggregate = eventMapper.aggregateUserMetrics(request.getUserId(),
                now.minusMinutes(10), now.minusHours(24), now.minusDays(7));
        Long deviceCount = deviceMapper.selectCount(new LambdaQueryWrapper<UserDevice>()
                .eq(UserDevice::getUserId, request.getUserId())
                .ge(UserDevice::getLastSeenTime, now.minusDays(30)));
        Long ipCount = ipMapper.selectCount(new LambdaQueryWrapper<UserIp>()
                .eq(UserIp::getUserId, request.getUserId())
                .ge(UserIp::getLastSeenTime, now.minusDays(30)));
        tracker.increment(3);
        long orderDelta = "ORDER".equals(request.getScene()) ? 1 : 0;
        values.put("USER_ORDER_COUNT_10M", nz(aggregate.getOrderCount10m()) + orderDelta);
        values.put("USER_ORDER_COUNT_24H", nz(aggregate.getOrderCount24h()) + orderDelta);
        values.put("USER_COMPLETED_COUNT", nz(aggregate.getCompletedCount()));
        values.put("USER_DISPUTE_COUNT_7D", nz(aggregate.getDisputeCount7d()));
        values.put("USER_DEVICE_COUNT_30D", nz(deviceCount));
        values.put("USER_IP_COUNT_30D", nz(ipCount));
        values.put("LOGIN_FAIL_COUNT_10M", nz(aggregate.getLoginFailCount10m()));
    }

    private void calculateLiveMerchantMetrics(Map<String, Object> values, RiskEvaluateRequest request,
                                              RiskQueryTracker tracker, LocalDateTime now) {
        if (request.getMerchantId() == null) {
            return;
        }
        MerchantMetricAggregate aggregate = eventMapper.aggregateMerchantMetrics(request.getMerchantId(),
                now.minusDays(30), now.minusHours(24));
        tracker.increment();
        long completed = nz(aggregate.getCompletedCount());
        long refunds = nz(aggregate.getRefundCount30d());
        long disputes = nz(aggregate.getDisputeCount30d());
        values.put("MERCHANT_COMPLETED_COUNT", completed);
        values.put("MERCHANT_REFUND_COUNT_30D", refunds);
        values.put("MERCHANT_DISPUTE_COUNT_30D", disputes);
        values.put("MERCHANT_REFUND_RATE_30D", completed == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(refunds).divide(BigDecimal.valueOf(completed), 4, RoundingMode.HALF_UP));
        values.put("MERCHANT_DISPUTE_RATE_30D", completed == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(disputes).divide(BigDecimal.valueOf(completed), 4, RoundingMode.HALF_UP));
        BigDecimal withdrawn = aggregate.getWithdrawAmount24h() == null ? BigDecimal.ZERO
                : aggregate.getWithdrawAmount24h();
        values.put("MERCHANT_WITHDRAW_AMOUNT_24H", withdrawn.add(
                "WITHDRAW".equals(request.getScene()) && request.getAmount() != null
                        ? request.getAmount() : BigDecimal.ZERO));
    }

    private void applyUserSnapshot(Map<String, Object> values, Set<String> missing,
                                   RiskIndicatorService.IndicatorSnapshot snapshot,
                                   RiskEvaluateRequest request) {
        if (snapshot == null) {
            missing.addAll(USER_METRICS);
            return;
        }
        values.putAll(snapshot.values());
        missing.addAll(snapshot.missingMetrics());
        if ("ORDER".equals(request.getScene())) {
            increase(values, "USER_ORDER_COUNT_10M");
            increase(values, "USER_ORDER_COUNT_24H");
        }
    }

    private void applyMerchantSnapshot(Map<String, Object> values, Set<String> missing,
                                       RiskIndicatorService.IndicatorSnapshot snapshot,
                                       RiskEvaluateRequest request) {
        if (snapshot == null) {
            missing.addAll(MERCHANT_METRICS);
            return;
        }
        values.putAll(snapshot.values());
        missing.addAll(snapshot.missingMetrics());
        if ("WITHDRAW".equals(request.getScene()) && request.getAmount() != null) {
            BigDecimal current = number(values.get("MERCHANT_WITHDRAW_AMOUNT_24H"));
            values.put("MERCHANT_WITHDRAW_AMOUNT_24H", (current == null ? BigDecimal.ZERO : current)
                    .add(request.getAmount()));
        }
    }

    private void recordCacheMetrics(Map<String, RiskIndicatorService.IndicatorSnapshot> snapshots) {
        boolean hit = !snapshots.isEmpty() && snapshots.values().stream()
                .allMatch(snapshot -> snapshot.fresh() && snapshot.missingMetrics().isEmpty());
        if (hit) {
            meterRegistry.counter("risk_indicator_cache_hit_total").increment();
        } else {
            meterRegistry.counter("risk_indicator_cache_miss_total").increment();
        }
    }

    private void increase(Map<String, Object> values, String metricCode) {
        BigDecimal current = number(values.get(metricCode));
        values.put(metricCode, (current == null ? BigDecimal.ZERO : current).add(BigDecimal.ONE));
    }

    private long nz(Long value) {
        return value == null ? 0 : value;
    }

    private void putNumber(Map<String, Object> values, String code, BigDecimal value) {
        if (value != null) {
            values.put(code, value);
        }
    }

    private void putText(Map<String, Object> values, String code, String value) {
        if (value != null) {
            values.put(code, value);
        }
    }

    private BigDecimal number(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        try {
            return value == null ? null : new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    public record Metrics(Map<String, Object> values, Set<String> missing, int queryCount, long durationMs) {
    }
}
