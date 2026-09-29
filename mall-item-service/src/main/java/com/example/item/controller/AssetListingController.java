package com.example.item.controller;

import api.context.UserContext;
import api.trade.MerchantDTO;
import api.trade.MerchantDubboService;
import com.example.item.entity.Item;
import com.example.item.service.AssetListingService;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/asset")
@CrossOrigin(origins = "*")
public class AssetListingController {
    private final AssetListingService listingService;
    @DubboReference(timeout = 5000, retries = 0, check = false)
    private MerchantDubboService merchantService;

    public AssetListingController(AssetListingService listingService) {
        this.listingService = listingService;
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
    public Map<String, Object> list() {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            return response(401, "请先登录", null);
        }
        return response(200, "success", listingService.listBySeller(userId));
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
            int count = listingService.importCards(id, merchant.getId(), secrets);
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
    public Map<String, Object> pending() {
        Long adminId = UserContext.getUserId();
        if (adminId == null || !merchantService.isAdmin(adminId)) {
            return response(403, "无管理员权限", null);
        }
        return response(200, "success", listingService.listPending());
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
}
