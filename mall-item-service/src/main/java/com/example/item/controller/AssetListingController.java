package com.example.item.controller;

import api.common.PageQuery;
import api.common.PageResult;
import api.common.TimeRangeQuery;
import api.context.UserContext;
import api.trade.MerchantDTO;
import api.trade.MerchantDubboService;
import com.example.item.dto.ItemListQuery;
import com.example.item.dto.PendingItemListQuery;
import com.example.item.entity.Item;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import com.example.item.service.AssetListingService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.format.annotation.DateTimeFormat;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/asset")
public class AssetListingController {
    private final AssetListingService listingService;
    private final MeterRegistry meterRegistry;
    @Value("${pagination.default-page-size:20}")
    private int defaultPageSize;
    @Value("${pagination.max-page-size:100}")
    private int maxPageSize;
    @DubboReference(timeout = 5000, retries = 0, check = false)
    private MerchantDubboService merchantService;

    public AssetListingController(AssetListingService listingService, MeterRegistry meterRegistry) {
        this.listingService = listingService;
        this.meterRegistry = meterRegistry;
    }

    @PostMapping("/item")
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            return response(401, "请先登录", null);
        }
        try {
            MerchantDTO merchant = requireApprovedMerchant(userId);
            requireEnoughDeposit(merchant);
            return response(200, "商品已创建", listingService.create(userId, merchant.getId(), body));
        } catch (Exception e) {
            return response(400, e.getMessage(), null);
        }
    }

    @GetMapping("/item/list")
    public Map<String, Object> list(@RequestParam(required = false) Integer page,
                                    @RequestParam(required = false) Integer pageSize,
                                    @RequestParam(required = false) String auditStatus,
                                    @RequestParam(required = false) String assetType,
                                    @RequestParam(required = false) String keyword) {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            return response(401, "请先登录", null);
        }
        try {
            PageQuery pagination = PageQuery.of(page, pageSize, defaultPageSize, maxPageSize);
            requireAuditStatus(auditStatus);
            requireAssetType(assetType);
            if (keyword != null && keyword.length() > 64) {
                throw new IllegalArgumentException("keyword 最长 64 个字符");
            }
            ItemListQuery query = new ItemListQuery(auditStatus, assetType,
                    keyword == null || keyword.isBlank() ? null : keyword.trim());
            PageResult<?> result = Timer.builder("item_list_duration_seconds")
                    .register(meterRegistry)
                    .record(() -> listingService.listBySeller(userId, query,
                            pagination.page(), pagination.pageSize()));
            return response(200, "success", result);
        } catch (IllegalArgumentException e) {
            return response(400, e.getMessage(), null);
        }
    }

    @PostMapping("/item/{id}/submit")
    public Map<String, Object> submit(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            return response(401, "请先登录", null);
        }
        try {
            MerchantDTO merchant = requireApprovedMerchant(userId);
            requireEnoughDeposit(merchant);
            return response(200, "已提交审核", listingService.submit(id, merchant.getId(), merchant, body));
        } catch (Exception e) {
            return response(400, e.getMessage(), null);
        }
    }

    @PostMapping("/item/{id}/cards")
    public Map<String, Object> importCards(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            return response(401, "请先登录", null);
        }
        try {
            MerchantDTO merchant = requireApprovedMerchant(userId);
            @SuppressWarnings("unchecked")
            List<String> secrets = (List<String>) body.get("secrets");
            int count = Timer.builder("card_import_duration_seconds")
                    .register(meterRegistry)
                    .record(() -> listingService.importCards(id, merchant.getId(), userId, secrets));
            return response(200, "卡密已加密入库", count);
        } catch (Exception e) {
            return response(400, e.getMessage(), null);
        }
    }

    @PostMapping("/admin/item/{id}/audit")
    public Map<String, Object> audit(@PathVariable Long id, @RequestBody Map<String, String> body) {
        Long adminId = UserContext.getUserId();
        if (adminId == null || !merchantService.isAdmin(adminId)) {
            return response(403, "无管理员权限", null);
        }
        try {
            return response(200, "审核完成", listingService.audit(id, adminId,
                    body.getOrDefault("action", ""), body.get("reason")));
        } catch (Exception e) {
            return response(400, e.getMessage(), null);
        }
    }

    @GetMapping("/admin/item/pending")
    public Map<String, Object> pending(@RequestParam(required = false) Integer page,
                                       @RequestParam(required = false) Integer pageSize,
                                       @RequestParam(required = false) Long merchantId,
                                       @RequestParam(required = false) String assetType,
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
            requireAssetType(assetType);
            PendingItemListQuery query = new PendingItemListQuery(merchantId, assetType, timeRange);
            PageResult<?> result = Timer.builder("item_pending_list_duration_seconds")
                    .register(meterRegistry)
                    .record(() -> listingService.listPending(query,
                            pagination.page(), pagination.pageSize()));
            return response(200, "success", result);
        } catch (IllegalArgumentException e) {
            return response(400, e.getMessage(), null);
        }
    }

    private MerchantDTO requireApprovedMerchant(Long userId) {
        MerchantDTO merchant = merchantService.getApprovedMerchantByUserId(userId);
        if (merchant == null) {
            throw new IllegalArgumentException("请先成为已审核商家");
        }
        return merchant;
    }

    private void requireEnoughDeposit(MerchantDTO merchant) {
        BigDecimal available = merchant.getTotalDeposit()
                .subtract(merchant.getFrozenDeposit())
                .subtract(merchant.getDeductedDeposit());
        if (available.compareTo(new BigDecimal("100.00")) < 0) {
            throw new IllegalArgumentException("可用保证金不足 100.00，不能发布商品");
        }
    }

    private Map<String, Object> response(int code, String message, Object data) {
        Map<String, Object> result = new HashMap<>();
        result.put("code", code);
        result.put("msg", message);
        result.put("data", data);
        return result;
    }

    private void requireAuditStatus(String status) {
        if (status != null && !java.util.Set.of("DRAFT", "PENDING", "APPROVED", "REJECTED").contains(status)) {
            throw new IllegalArgumentException("auditStatus 不合法");
        }
    }

    private void requireAssetType(String assetType) {
        if (assetType != null && !java.util.Set.of("CARD", "VIRTUAL_SKIN", "GAME_ITEM").contains(assetType)) {
            throw new IllegalArgumentException("assetType 不合法");
        }
    }
}
