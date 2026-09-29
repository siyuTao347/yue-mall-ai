package com.example.user.controller;

import api.context.UserContext;
import com.example.user.entity.WithdrawRequest;
import com.example.user.service.MerchantService;
import com.example.user.service.WithdrawService;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/withdraw")
@CrossOrigin(origins = "*")
public class WithdrawController {
    private final WithdrawService withdrawService;
    private final MerchantService merchantService;

    public WithdrawController(WithdrawService withdrawService, MerchantService merchantService) {
        this.withdrawService = withdrawService;
        this.merchantService = merchantService;
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
    public Map<String, Object> list() {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            return response(401, "请先登录", null);
        }
        return response(200, "success", withdrawService.listByUser(userId));
    }

    @GetMapping("/admin/pending")
    public Map<String, Object> pending() {
        Long adminId = UserContext.getUserId();
        if (adminId == null || !merchantService.isAdmin(adminId)) {
            return response(403, "无管理员权限", null);
        }
        return response(200, "success", withdrawService.listPending());
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
}
