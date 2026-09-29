package com.example.risk.service;

import api.risk.RiskEvaluateRequest;
import api.risk.SensitiveWordHitDTO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.risk.entity.UserDevice;
import com.example.risk.entity.UserIp;
import com.example.risk.mapper.RiskEventMapper;
import com.example.risk.mapper.UserDeviceMapper;
import com.example.risk.mapper.UserIpMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Service
public class RiskMetricService {
    private final RiskEventMapper eventMapper;
    private final UserDeviceMapper deviceMapper;
    private final UserIpMapper ipMapper;
    private final RiskIdentityService identityService;

    public RiskMetricService(RiskEventMapper eventMapper, UserDeviceMapper deviceMapper,
                             UserIpMapper ipMapper, RiskIdentityService identityService) {
        this.eventMapper = eventMapper;
        this.deviceMapper = deviceMapper;
        this.ipMapper = ipMapper;
        this.identityService = identityService;
    }

    public Metrics calculate(RiskEvaluateRequest request) {
        Map<String, Object> values = new LinkedHashMap<>();
        Set<String> missing = new HashSet<>();
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
        putNumber(values, "REGISTER_SAME_IP_COUNT_10M", request.getIpHash() == null ? null
                : BigDecimal.valueOf(eventMapper.countIpEvents("REGISTER", request.getIpHash(),
                now.minusMinutes(10)) + ("REGISTER".equals(request.getScene()) ? 1 : 0)));
        putNumber(values, "REGISTER_SAME_DEVICE_COUNT_30D", request.getDeviceHash() == null ? null
                : BigDecimal.valueOf(eventMapper.countDeviceEvents("REGISTER", request.getDeviceHash(),
                now.minusDays(30)) + ("REGISTER".equals(request.getScene()) ? 1 : 0)));
        putNumber(values, "LOGIN_FAIL_COUNT_10M", BigDecimal.valueOf(
                eventMapper.countUserTypedEvents("LOGIN", "LOGIN_FAIL",
                        request.getUserId(), now.minusMinutes(10))));

        if (request.getUserId() != null) {
            values.put("USER_ORDER_COUNT_10M", eventMapper.countUserEvents("ORDER", request.getUserId(),
                    now.minusMinutes(10)) + ("ORDER".equals(request.getScene()) ? 1 : 0));
            values.put("USER_ORDER_COUNT_24H", eventMapper.countUserEvents("ORDER", request.getUserId(),
                    now.minusHours(24)) + ("ORDER".equals(request.getScene()) ? 1 : 0));
            values.put("USER_COMPLETED_COUNT", eventMapper.countUserTotalTypedEvents(
                    "CONFIRM", "CONFIRM", request.getUserId()));
            values.put("USER_DISPUTE_COUNT_7D", eventMapper.countUserTypedEvents("DISPUTE", "OPEN",
                    request.getUserId(), now.minusDays(7)));
            values.put("USER_DEVICE_COUNT_30D", deviceMapper.selectCount(new LambdaQueryWrapper<UserDevice>()
                    .eq(UserDevice::getUserId, request.getUserId())
                    .ge(UserDevice::getLastSeenTime, now.minusDays(30))));
            values.put("USER_IP_COUNT_30D", ipMapper.selectCount(new LambdaQueryWrapper<UserIp>()
                    .eq(UserIp::getUserId, request.getUserId())
                    .ge(UserIp::getLastSeenTime, now.minusDays(30))));
        }

        if (request.getMerchantId() != null) {
            values.put("MERCHANT_COMPLETED_COUNT", eventMapper.countMerchantTotalTypedEvents(
                    "CONFIRM", "CONFIRM", request.getMerchantId()));
            long refunds = eventMapper.countMerchantTypedEvents("DISPUTE", "REFUND",
                    request.getMerchantId(), now.minusDays(30));
            long disputes = eventMapper.countMerchantTypedEvents("DISPUTE", "OPEN",
                    request.getMerchantId(), now.minusDays(30));
            long completed = ((Number) values.getOrDefault("MERCHANT_COMPLETED_COUNT", 0L)).longValue();
            values.put("MERCHANT_REFUND_RATE_30D", completed == 0 ? BigDecimal.ZERO
                    : BigDecimal.valueOf(refunds).divide(BigDecimal.valueOf(completed), 4, RoundingMode.HALF_UP));
            values.put("MERCHANT_DISPUTE_RATE_30D", completed == 0 ? BigDecimal.ZERO
                    : BigDecimal.valueOf(disputes).divide(BigDecimal.valueOf(completed), 4, RoundingMode.HALF_UP));
            BigDecimal withdrawn = eventMapper.sumMerchantWithdraw(request.getMerchantId(), now.minusHours(24));
            values.put("MERCHANT_WITHDRAW_AMOUNT_24H", withdrawn.add(
                    "WITHDRAW".equals(request.getScene()) && request.getAmount() != null
                            ? request.getAmount() : BigDecimal.ZERO));
            String accountHash = text(payload.get("withdrawAccountHash"));
            if (accountHash != null) {
                values.put("WITHDRAW_ACCOUNT_MERCHANT_COUNT", eventMapper.countWithdrawAccountMerchants(
                        accountHash, now.minusDays(180)) + 1);
            }
        }

        BigDecimal median = number(payload.get("categoryMedianPrice"));
        if (median != null && median.compareTo(BigDecimal.ZERO) > 0 && request.getAmount() != null) {
            values.put("ITEM_PRICE_DEVIATION", request.getAmount()
                    .divide(median, 4, RoundingMode.HALF_UP)
                    .subtract(BigDecimal.ONE));
        }
        values.put("BUYER_SELLER_RELATION", identityService.buyerSellerRelation(request));
        if (request.getSensitiveHits() != null && !request.getSensitiveHits().isEmpty()) {
            values.put("SENSITIVE_WORD_CATEGORY", request.getSensitiveHits().stream()
                    .map(SensitiveWordHitDTO::getCategory)
                    .findFirst().orElse(null));
        }
        return new Metrics(values, missing);
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

    public record Metrics(Map<String, Object> values, Set<String> missing) {
    }
}
