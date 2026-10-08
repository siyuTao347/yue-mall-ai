package com.example.user.controller;

import api.common.PageQuery;
import api.common.PageResult;
import api.context.UserContext;
import api.common.TimeRangeQuery;
import com.example.user.dto.FundFlowListQuery;
import com.example.user.service.FundService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/account")
public class AccountController {
    private final FundService fundService;
    private final MeterRegistry meterRegistry;

    public AccountController(FundService fundService, MeterRegistry meterRegistry) {
        this.fundService = fundService;
        this.meterRegistry = meterRegistry;
    }

    @GetMapping("/me")
    public Map<String, Object> account() {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            return response(401, "请先登录", null);
        }
        return response(200, "success", fundService.getAccount(userId));
    }

    @GetMapping("/flows")
    public Map<String, Object> flows(@RequestParam(required = false) Integer page,
                                     @RequestParam(required = false) Integer pageSize,
                                     @RequestParam(required = false) String accountType,
                                     @RequestParam(required = false)
                                     @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime fromTime,
                                     @RequestParam(required = false)
                                     @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime toTime) {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            return response(401, "请先登录", null);
        }
        try {
            PageQuery pagination = PageQuery.of(page, pageSize, 20, 100);
            TimeRangeQuery timeRange = new TimeRangeQuery(fromTime, toTime);
            timeRange.validate(92);
            if (accountType != null && !ACCOUNT_TYPES.contains(accountType)) {
                throw new IllegalArgumentException("accountType 不合法");
            }
            FundFlowListQuery query = new FundFlowListQuery(accountType, timeRange);
            PageResult<?> result = Timer.builder("fund_flow_list_duration_seconds")
                    .register(meterRegistry)
                    .record(() -> fundService.listFlows(query, userId, pagination.page(), pagination.pageSize()));
            return response(200, "success", result);
        } catch (IllegalArgumentException e) {
            return response(400, e.getMessage(), null);
        }
    }

    private Map<String, Object> response(int code, String message, Object data) {
        Map<String, Object> result = new HashMap<>();
        result.put("code", code);
        result.put("msg", message);
        result.put("data", data);
        return result;
    }

    private static final java.util.Set<String> ACCOUNT_TYPES = java.util.Set.of(
            "AVAILABLE", "PENDING_SETTLE", "FROZEN", "ESCROW", "DEPOSIT", "REVENUE",
            "WITHDRAW_PENDING", "EXTERNAL_CHANNEL");
}
