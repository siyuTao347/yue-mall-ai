package com.example.user.controller;

import api.context.UserContext;
import com.example.user.entity.Merchant;
import com.example.user.entity.MerchantDeposit;
import com.example.user.service.MerchantService;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/merchant")
@CrossOrigin(origins = "*")
public class MerchantController {
    private final MerchantService merchantService;

    public MerchantController(MerchantService merchantService) {
        this.merchantService = merchantService;
    }

    @PostMapping("/apply")
    public Map<String, Object> apply(@RequestBody Map<String, String> body) {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            return response(401, "请先登录", null);
        }
        try {
            Merchant merchant = merchantService.apply(userId, body.get("merchantName"),
                    body.get("contactEmail"), body.get("introduction"));
            return response(200, "申请已提交", merchant);
        } catch (Exception e) {
            return response(400, e.getMessage(), null);
        }
    }

    @GetMapping("/me")
    public Map<String, Object> me() {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            return response(401, "请先登录", null);
        }
        Merchant merchant = merchantService.getByUserId(userId);
        if (merchant == null) {
            return response(404, "尚未提交商家申请", null);
        }
        return response(200, "success", merchant);
    }

    @PostMapping("/{id}/deposit")
    public Map<String, Object> payDeposit(@PathVariable Long id, @RequestBody Map<String, String> body) {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            return response(401, "请先登录", null);
        }
        try {
            BigDecimal amount = new BigDecimal(body.getOrDefault("amount", "0"));
            MerchantDeposit deposit = merchantService.payDeposit(body.get("depositNo"), id, userId, amount);
            return response(200, "Mock 保证金缴纳成功", deposit);
        } catch (Exception e) {
            return response(400, e.getMessage(), null);
        }
    }

    @GetMapping("/{id}/deposit")
    public Map<String, Object> getDeposit(@PathVariable Long id) {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            return response(401, "请先登录", null);
        }
        try {
            return response(200, "success", merchantService.getDeposit(id, userId));
        } catch (Exception e) {
            return response(404, e.getMessage(), null);
        }
    }

    @GetMapping("/{id}/credit")
    public Map<String, Object> getCredit(@PathVariable Long id) {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            return response(401, "请先登录", null);
        }
        try {
            return response(200, "success", merchantService.getCredit(id, userId));
        } catch (Exception e) {
            return response(404, e.getMessage(), null);
        }
    }

    @PostMapping("/admin/{id}/audit")
    public Map<String, Object> audit(@PathVariable Long id, @RequestBody Map<String, String> body) {
        Long adminId = UserContext.getUserId();
        if (adminId == null || !merchantService.isAdmin(adminId)) {
            return response(403, "无管理员权限", null);
        }
        try {
            Merchant merchant = merchantService.audit(id, adminId,
                    body.getOrDefault("action", ""), body.get("reason"));
            return response(200, "审核完成", merchant);
        } catch (Exception e) {
            return response(400, e.getMessage(), null);
        }
    }

    @GetMapping("/admin/list")
    public Map<String, Object> list(@RequestParam(required = false) String status) {
        Long adminId = UserContext.getUserId();
        if (adminId == null || !merchantService.isAdmin(adminId)) {
            return response(403, "无管理员权限", null);
        }
        List<Merchant> merchants = merchantService.listByStatus(status);
        return response(200, "success", merchants);
    }

    private Map<String, Object> response(int code, String message, Object data) {
        Map<String, Object> result = new HashMap<>();
        result.put("code", code);
        result.put("msg", message);
        result.put("data", data);
        return result;
    }
}
