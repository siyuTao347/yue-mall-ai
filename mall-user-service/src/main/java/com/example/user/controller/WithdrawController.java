package com.example.user.controller;

import api.common.PageQuery;
import api.common.PageResult;
import api.common.TimeRangeQuery;
import api.context.UserContext;
import com.example.user.dto.WithdrawListQuery;
import com.example.user.entity.WithdrawRequest;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.format.annotation.DateTimeFormat;
import com.example.user.service.MerchantService;
import com.example.user.service.WithdrawService;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/withdraw")
public class WithdrawController {
    private final WithdrawService withdrawService;
    private final MerchantService merchantService;
    private final MeterRegistry meterRegistry;
    @Value("${pagination.default-page-size:20}")
    private int defaultPageSize;
    @Value("${pagination.max-page-size:100}")
    private int maxPageSize;

    public WithdrawController(WithdrawService withdrawService, MerchantService merchantService,
                              MeterRegistry meterRegistry) {
        this.withdrawService = withdrawService;
        this.merchantService = merchantService;
        this.meterRegistry = meterRegistry;
    }

    @PostMapping("/apply")
    public Map<String, Object> apply(@RequestBody Map<String, String> body) {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            return response(401, "请先登录", null);
        }
        try {
            com.example.user.entity.Merchant merchant = merchantService.getByUserId(userId);
            if (merchant == null) {
                return response(400, "请先成为商家", null);
            }
            WithdrawRequest request = withdrawService.apply(merchant.getId(), userId,
                    new BigDecimal(body.getOrDefault("amount", "0")), body.get("mockAccount"),
                    body.get("clientToken"));
            return response(200, "提现申请已提交", request);
        } catch (Exception e) {
            return response(400, e.getMessage(), null);
        }
    }

    @GetMapping("/list")
    public Map<String, Object> list(@RequestParam(required = false) Integer page,
                                    @RequestParam(required = false) Integer pageSize,
                                    @RequestParam(required = false) String status,
                                    @RequestParam(required = false)
                                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime fromTime,
                                    @RequestParam(required = false)
                                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime toTime) {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            return response(401, "请先登录", null);
        }
        try {
            PageQuery pagination = PageQuery.of(page, pageSize, defaultPageSize, maxPageSize);
            TimeRangeQuery timeRange = new TimeRangeQuery(fromTime, toTime);
            timeRange.validate(92);
            requireStatus(status);
            WithdrawListQuery query = new WithdrawListQuery(status, null, null, timeRange);
            PageResult<?> result = Timer.builder("withdraw_list_duration_seconds")
                    .register(meterRegistry)
                    .record(() -> withdrawService.listByUser(userId, query,
                            pagination.page(), pagination.pageSize()));
            return response(200, "success", result);
        } catch (IllegalArgumentException e) {
            return response(400, e.getMessage(), null);
        }
    }

    @GetMapping("/admin/pending")
    public Map<String, Object> pending(@RequestParam(required = false) Integer page,
                                       @RequestParam(required = false) Integer pageSize,
                                       @RequestParam(required = false) Long merchantId,
                                       @RequestParam(required = false) Long userId,
                                       @RequestParam(required = false)
                                       @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime fromTime,
                                       @RequestParam(required = false)
                                       @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime toTime) {
        Long adminId = UserContext.getUserId();
        if (adminId == null || !merchantService.isAdmin(adminId)) {
            return response(403, "无管理员权限", null);
        }
        try {
            PageQuery pagination = PageQuery.of(page, pageSize, defaultPageSize, maxPageSize);
            TimeRangeQuery timeRange = new TimeRangeQuery(fromTime, toTime);
            timeRange.validate(92);
            WithdrawListQuery query = new WithdrawListQuery("SUBMITTED", merchantId, userId, timeRange);
            PageResult<?> result = Timer.builder("withdraw_pending_list_duration_seconds")
                    .register(meterRegistry)
                    .record(() -> withdrawService.listPending(query, pagination.page(), pagination.pageSize()));
            return response(200, "success", result);
        } catch (IllegalArgumentException e) {
            return response(400, e.getMessage(), null);
        }
    }

    @PostMapping("/admin/audit")
    public Map<String, Object> audit(@RequestBody Map<String, Object> body) {
        Long adminId = UserContext.getUserId();
        if (adminId == null || !merchantService.isAdmin(adminId)) {
            return response(403, "无管理员权限", null);
        }
        try {
            String withdrawNo = String.valueOf(body.get("withdrawNo"));
            boolean approved = Boolean.parseBoolean(String.valueOf(body.get("approved")));
            WithdrawRequest request = withdrawService.audit(withdrawNo, adminId, approved,
                    String.valueOf(body.getOrDefault("reason", "")));
            return response(200, "提现审核完成", request);
        } catch (Exception e) {
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

    private void requireStatus(String status) {
        if (status != null && !STATUSES.contains(status)) {
            throw new IllegalArgumentException("status 不合法");
        }
    }

    private static final java.util.Set<String> STATUSES =
            java.util.Set.of("SUBMITTED", "PAYOUT_SUCCESS", "REJECTED");
}
