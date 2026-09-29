package com.example.user.controller;

import api.context.UserContext;
import com.example.user.entity.FundFlow;
import com.example.user.entity.UserAccount;
import com.example.user.service.FundService;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/account")
@CrossOrigin(origins = "*")
public class AccountController {
    private final FundService fundService;

    public AccountController(FundService fundService) {
        this.fundService = fundService;
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
    public Map<String, Object> flows() {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            return response(401, "请先登录", null);
        }
        List<FundFlow> flows = fundService.getFlows(userId);
        return response(200, "success", flows);
    }

    private Map<String, Object> response(int code, String message, Object data) {
        Map<String, Object> result = new HashMap<>();
        result.put("code", code);
        result.put("msg", message);
        result.put("data", data);
        return result;
    }
}
