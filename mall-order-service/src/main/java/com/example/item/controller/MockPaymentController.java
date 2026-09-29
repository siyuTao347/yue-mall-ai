package com.example.item.controller;

import api.context.UserContext;
import com.example.item.entity.PaymentOrder;
import com.example.item.service.TradeOrderService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

@RestController
@RequestMapping("/api/payment")
public class MockPaymentController {
    private final TradeOrderService tradeOrderService;
    private final ObjectMapper objectMapper;

    public MockPaymentController(TradeOrderService tradeOrderService, ObjectMapper objectMapper) {
        this.tradeOrderService = tradeOrderService;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/{paymentNo}")
    public Map<String, Object> detail(@PathVariable String paymentNo) {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            return response(401, "请先登录", null);
        }
        try {
            PaymentOrder payment = getVisiblePayment(paymentNo, userId);
            return response(200, "success", payment);
        } catch (Exception e) {
            return response(404, e.getMessage(), null);
        }
    }

    @PostMapping("/{paymentNo}/start")
    public Map<String, Object> start(@PathVariable String paymentNo) {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            return response(401, "请先登录", null);
        }
        try {
            getVisiblePayment(paymentNo, userId);
            return response(200, "支付中", tradeOrderService.startPay(paymentNo));
        } catch (Exception e) {
            return response(400, e.getMessage(), null);
        }
    }

    @PostMapping("/{paymentNo}/mock-pay")
    public Map<String, Object> mockPay(@PathVariable String paymentNo, @RequestBody Map<String, Object> body) {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            return response(401, "请先登录", null);
        }
        try {
            PaymentOrder payment = getVisiblePayment(paymentNo, userId);
            String result = "FAIL".equalsIgnoreCase(String.valueOf(body.get("result"))) ? "FAIL" : "SUCCESS";
            String callbackNo = "CB" + System.currentTimeMillis() + paymentNo.hashCode();
            String signature = signature(payment.getPaymentNo(), payment.getAmount(), payment.getCallbackTokenHash());
            String callbackResult = tradeOrderService.handleCallback(callbackNo, paymentNo,
                    payment.getOrderNo(), payment.getAmount(), result, signature,
                    objectMapper.writeValueAsString(body));
            return response("SUCCESS".equals(callbackResult) ? 200 : 409, callbackResult,
                    tradeOrderService.getVisibleOrder(userId, payment.getOrderNo()));
        } catch (Exception e) {
            return response(400, e.getMessage(), null);
        }
    }

    @PostMapping("/callback")
    public Map<String, Object> callback(@RequestBody Map<String, Object> body) {
        try {
            String paymentNo = String.valueOf(body.get("paymentNo"));
            PaymentOrder payment = tradeOrderService.getPaymentByNo(paymentNo);
            BigDecimal amount = new BigDecimal(String.valueOf(body.get("amount")));
            String result = String.valueOf(body.get("result"));
            String callbackResult = tradeOrderService.handleCallback(String.valueOf(body.get("callbackNo")),
                    paymentNo, payment.getOrderNo(), amount, result,
                    String.valueOf(body.get("signature")), objectMapper.writeValueAsString(body));
            return response("SUCCESS".equals(callbackResult) ? 200 : 409, callbackResult, null);
        } catch (Exception e) {
            return response(400, e.getMessage(), null);
        }
    }

    private PaymentOrder getVisiblePayment(String paymentNo, Long userId) {
        PaymentOrder payment = tradeOrderService.getPaymentByNo(paymentNo);
        tradeOrderService.getVisibleOrder(userId, payment.getOrderNo());
        return payment;
    }

    private String signature(String paymentNo, BigDecimal amount, String tokenHash) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String raw = paymentNo + "|" + amount + "|" + tokenHash;
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Mock 支付签名生成失败", e);
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
