package com.example.item.controller;

import api.context.UserContext;
import api.trade.MerchantDubboService;
import com.example.item.entity.Arbitration;
import com.example.item.entity.DeliveryEvidence;
import com.example.item.entity.Dispute;
import com.example.item.entity.DisputeMessage;
import com.example.item.entity.OrderReview;
import com.example.item.entity.PaymentOrder;
import com.example.item.entity.TradeOrder;
import com.example.item.entity.TradeOrderStatusLog;
import com.example.item.service.TradeOrderService;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/trade")
public class TradeOrderController {
    private final TradeOrderService tradeOrderService;
    @DubboReference(timeout = 5000, retries = 0, check = false)
    private MerchantDubboService merchantService;

    public TradeOrderController(TradeOrderService tradeOrderService) {
        this.tradeOrderService = tradeOrderService;
    }

    @PostMapping("/orders")
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        Long buyerId = UserContext.getUserId();
        if (buyerId == null) {
            return response(401, "请先登录", null);
        }
        try {
            TradeOrder order = tradeOrderService.create(buyerId,
                    Long.valueOf(String.valueOf(body.get("itemId"))),
                    Integer.valueOf(String.valueOf(body.getOrDefault("quantity", "1"))));
            return response(200, "担保订单已创建", order);
        } catch (Exception e) {
            return response(400, e.getMessage(), null);
        }
    }

    @GetMapping("/orders")
    public Map<String, Object> list() {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            return response(401, "请先登录", null);
        }
        return response(200, "success", tradeOrderService.listByUser(userId));
    }

    @GetMapping("/orders/{orderNo}")
    public Map<String, Object> detail(@PathVariable String orderNo) {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            return response(401, "请先登录", null);
        }
        try {
            return response(200, "success", tradeOrderService.getVisibleOrder(userId, orderNo));
        } catch (Exception e) {
            return response(404, e.getMessage(), null);
        }
    }

    @PostMapping("/orders/{orderNo}/cancel")
    public Map<String, Object> cancel(@PathVariable String orderNo) {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            return response(401, "请先登录", null);
        }
        try {
            int rows = tradeOrderService.cancelUnpaidOrder(userId, orderNo, "买家取消未支付订单");
            return response(rows > 0 ? 200 : 409, rows > 0 ? "订单已取消" : "当前订单不能取消", null);
        } catch (Exception e) {
            return response(400, e.getMessage(), null);
        }
    }

    @PostMapping("/orders/{orderNo}/deliver")
    public Map<String, Object> deliver(@PathVariable String orderNo,
                                       @RequestBody Map<String, String> body) {
        Long sellerId = UserContext.getUserId();
        if (sellerId == null) {
            return response(401, "请先登录", null);
        }
        try {
            return response(200, "交付成功", tradeOrderService.deliver(sellerId, orderNo, body.get("content")));
        } catch (Exception e) {
            return response(400, e.getMessage(), null);
        }
    }

    @GetMapping("/orders/{orderNo}/secrets")
    public Map<String, Object> viewSecrets(@PathVariable String orderNo) {
        Long buyerId = UserContext.getUserId();
        if (buyerId == null) {
            return response(401, "请先登录", null);
        }
        try {
            return response(200, "success", tradeOrderService.viewSecrets(buyerId, orderNo));
        } catch (Exception e) {
            return response(400, e.getMessage(), null);
        }
    }

    @GetMapping("/orders/{orderNo}/delivery")
    public Map<String, Object> viewDelivery(@PathVariable String orderNo) {
        Long buyerId = UserContext.getUserId();
        if (buyerId == null) {
            return response(401, "请先登录", null);
        }
        try {
            return response(200, "success", tradeOrderService.viewDelivery(buyerId, orderNo));
        } catch (Exception e) {
            return response(400, e.getMessage(), null);
        }
    }

    @PostMapping("/orders/{orderNo}/confirm")
    public Map<String, Object> confirm(@PathVariable String orderNo) {
        Long buyerId = UserContext.getUserId();
        if (buyerId == null) {
            return response(401, "请先登录", null);
        }
        try {
            return response(200, "确认成功", tradeOrderService.confirm(buyerId, orderNo, "BUYER"));
        } catch (Exception e) {
            return response(400, e.getMessage(), null);
        }
    }

    @PostMapping("/orders/{orderNo}/review")
    public Map<String, Object> review(@PathVariable String orderNo, @RequestBody Map<String, Object> body) {
        Long buyerId = UserContext.getUserId();
        if (buyerId == null) {
            return response(401, "请先登录", null);
        }
        try {
            OrderReview review = tradeOrderService.review(buyerId, orderNo,
                    Integer.valueOf(String.valueOf(body.get("score"))), String.valueOf(body.get("content")));
            return response(200, "评价成功", review);
        } catch (Exception e) {
            return response(400, e.getMessage(), null);
        }
    }

    @PostMapping("/orders/{orderNo}/evidence")
    public Map<String, Object> addEvidence(@PathVariable String orderNo, @RequestBody Map<String, String> body) {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            return response(401, "请先登录", null);
        }
        try {
            DeliveryEvidence evidence = tradeOrderService.addEvidence(userId, orderNo,
                    body.getOrDefault("evidenceType", "TEXT"), body.get("fileUrl"), body.get("content"));
            return response(200, "证据已提交", evidence);
        } catch (Exception e) {
            return response(400, e.getMessage(), null);
        }
    }

    @GetMapping("/orders/{orderNo}/evidence")
    public Map<String, Object> evidence(@PathVariable String orderNo) {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            return response(401, "请先登录", null);
        }
        try {
            return response(200, "success", tradeOrderService.getEvidence(userId, orderNo));
        } catch (Exception e) {
            return response(403, e.getMessage(), null);
        }
    }

    @GetMapping("/orders/{orderNo}/logs")
    public Map<String, Object> logs(@PathVariable String orderNo) {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            return response(401, "请先登录", null);
        }
        try {
            tradeOrderService.getVisibleOrder(userId, orderNo);
            return response(200, "success", tradeOrderService.getLogs(orderNo));
        } catch (Exception e) {
            return response(403, e.getMessage(), null);
        }
    }

    @PostMapping("/disputes")
    public Map<String, Object> openDispute(@RequestBody Map<String, Object> body) {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            return response(401, "请先登录", null);
        }
        try {
            BigDecimal refundAmount = body.get("refundAmount") == null
                    ? null : new BigDecimal(String.valueOf(body.get("refundAmount")));
            Dispute dispute = tradeOrderService.openDispute(userId, String.valueOf(body.get("orderNo")),
                    String.valueOf(body.get("disputeType")), String.valueOf(body.get("reason")), refundAmount);
            return response(200, "售后已提交", dispute);
        } catch (Exception e) {
            return response(400, e.getMessage(), null);
        }
    }

    @GetMapping("/disputes/{disputeNo}")
    public Map<String, Object> dispute(@PathVariable String disputeNo) {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            return response(401, "请先登录", null);
        }
        try {
            Dispute dispute = tradeOrderService.getDispute(userId, disputeNo);
            List<DisputeMessage> messages = tradeOrderService.listDisputeMessages(disputeNo);
            Map<String, Object> data = new HashMap<>();
            data.put("dispute", dispute);
            data.put("messages", messages);
            return response(200, "success", data);
        } catch (Exception e) {
            return response(403, e.getMessage(), null);
        }
    }

    @PostMapping("/disputes/{disputeNo}/messages")
    public Map<String, Object> addMessage(@PathVariable String disputeNo, @RequestBody Map<String, String> body) {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            return response(401, "请先登录", null);
        }
        try {
            return response(200, "留言成功", tradeOrderService.addDisputeMessage(userId, disputeNo, body.get("message")));
        } catch (Exception e) {
            return response(400, e.getMessage(), null);
        }
    }

    @GetMapping("/admin/disputes/pending")
    public Map<String, Object> pendingDisputes() {
        Long adminId = UserContext.getUserId();
        if (adminId == null || !merchantService.isAdmin(adminId)) {
            return response(403, "无管理员权限", null);
        }
        return response(200, "success", tradeOrderService.listPendingDisputes());
    }

    @GetMapping("/admin/disputes/{disputeNo}/evidence")
    public Map<String, Object> adminEvidence(@PathVariable String disputeNo) {
        Long adminId = UserContext.getUserId();
        if (adminId == null || !merchantService.isAdmin(adminId)) {
            return response(403, "无管理员权限", null);
        }
        try {
            Dispute dispute = tradeOrderService.getDisputeForAdmin(disputeNo);
            Map<String, Object> data = new HashMap<>();
            data.put("dispute", dispute);
            data.put("evidence", tradeOrderService.getEvidenceForAdmin(dispute.getOrderNo()));
            return response(200, "success", data);
        } catch (Exception e) {
            return response(400, e.getMessage(), null);
        }
    }

    @PostMapping("/admin/disputes/{disputeNo}/arbitrate")
    public Map<String, Object> arbitrate(@PathVariable String disputeNo, @RequestBody Map<String, String> body) {
        Long adminId = UserContext.getUserId();
        if (adminId == null || !merchantService.isAdmin(adminId)) {
            return response(403, "无管理员权限", null);
        }
        try {
            Arbitration arbitration = tradeOrderService.arbitrate(adminId, disputeNo,
                    body.get("result"), body.get("reason"));
            return response(200, "仲裁完成", arbitration);
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
