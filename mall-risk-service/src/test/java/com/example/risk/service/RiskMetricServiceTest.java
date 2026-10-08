package com.example.risk.service;

import api.risk.RiskEvaluateRequest;
import com.example.risk.mapper.RiskEventMapper;
import com.example.risk.mapper.UserDeviceMapper;
import com.example.risk.mapper.UserIpMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RiskMetricServiceTest {
    private RiskEventMapper eventMapper;
    private RiskIdentityService identityService;
    private RiskIndicatorService indicatorService;
    private RiskMetricService service;

    @BeforeEach
    void setUp() {
        eventMapper = mock(RiskEventMapper.class);
        identityService = mock(RiskIdentityService.class);
        indicatorService = mock(RiskIndicatorService.class);
        service = new RiskMetricService(eventMapper, mock(UserDeviceMapper.class),
                mock(UserIpMapper.class), identityService, indicatorService, new SimpleMeterRegistry());
        ReflectionTestUtils.setField(service, "materializedEnabled", true);
        ReflectionTestUtils.setField(service, "queryBudget", 3);
    }

    @Test
    void materializedOrderEvaluationStaysWithinQueryBudget() {
        when(indicatorService.metricsForUserAndMerchant(any(), any(), any(), any(),
                ArgumentMatchers.<RiskQueryTracker>any())).thenAnswer(invocation -> {
            RiskQueryTracker tracker = invocation.getArgument(4);
            tracker.increment();
            return Map.of(
                    "USER", new RiskIndicatorService.IndicatorSnapshot(userValues(), Set.of(), true),
                    "MERCHANT", new RiskIndicatorService.IndicatorSnapshot(merchantValues(), Set.of(), true));
        });
        when(identityService.buyerSellerRelation(any(RiskEvaluateRequest.class),
                ArgumentMatchers.<RiskQueryTracker>any())).thenAnswer(invocation -> {
            invocation.<RiskQueryTracker>getArgument(1).increment(2);
            return "NONE";
        });

        RiskEvaluateRequest request = RiskEvaluateRequest.builder()
                .eventNo("E1")
                .scene("ORDER")
                .eventType("CREATE")
                .userId(10L)
                .merchantId(20L)
                .amount(new BigDecimal("99.00"))
                .payload(Map.of("sellerId", 20L))
                .build();
        RiskMetricService.Metrics metrics = service.calculate(request);

        Assertions.assertTrue(metrics.queryCount() <= 3);
        Assertions.assertEquals(BigDecimal.valueOf(2), metrics.values().get("USER_ORDER_COUNT_10M"));
        Assertions.assertEquals(Set.of(), metrics.missing());
    }

    private Map<String, Object> userValues() {
        return Map.of(
                "USER_ORDER_COUNT_10M", BigDecimal.ONE,
                "USER_ORDER_COUNT_24H", BigDecimal.ONE,
                "USER_COMPLETED_COUNT", BigDecimal.ZERO,
                "USER_DISPUTE_COUNT_7D", BigDecimal.ZERO,
                "USER_DEVICE_COUNT_30D", BigDecimal.ZERO,
                "USER_IP_COUNT_30D", BigDecimal.ZERO,
                "LOGIN_FAIL_COUNT_10M", BigDecimal.ZERO);
    }

    private Map<String, Object> merchantValues() {
        return Map.of(
                "MERCHANT_COMPLETED_COUNT", BigDecimal.ZERO,
                "MERCHANT_REFUND_COUNT_30D", BigDecimal.ZERO,
                "MERCHANT_DISPUTE_COUNT_30D", BigDecimal.ZERO,
                "MERCHANT_REFUND_RATE_30D", BigDecimal.ZERO,
                "MERCHANT_DISPUTE_RATE_30D", BigDecimal.ZERO,
                "MERCHANT_WITHDRAW_AMOUNT_24H", BigDecimal.ZERO);
    }
}
