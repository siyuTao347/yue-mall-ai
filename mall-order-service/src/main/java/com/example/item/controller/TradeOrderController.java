package com.example.item.controller;

import api.context.UserContext;
import api.common.PageQuery;
import api.common.PageResult;
import api.common.TimeRangeQuery;
import api.trade.MerchantDubboService;
import api.trade.request.AddDisputeMessageRequest;
import api.trade.request.AddEvidenceRequest;
import api.trade.request.ArbitrateDisputeRequest;
import api.trade.request.CreateOrderRequest;
import api.trade.request.DeliverOrderRequest;
import api.trade.request.OpenDisputeRequest;
import api.trade.request.ReviewOrderRequest;
import com.example.item.dto.DisputeListQuery;
import com.example.item.dto.OrderListQuery;
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
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/trade")
public class TradeOrderController {
    private final TradeOrderService tradeOrderService;
    private final MeterRegistry meterRegistry;
    @Value("${pagination.default-page-size:20}")
    private int defaultPageSize;
    @Value("${pagination.max-page-size:100}")
    private int maxPageSize;
    @DubboReference(timeout = 5000, retries = 0, check = false)
    private MerchantDubboService merchantService;

    public TradeOrderController(TradeOrderService tradeOrderService, MeterRegistry meterRegistry) {
        this.tradeOrderService = tradeOrderService;
        this.meterRegistry = meterRegistry;
    }

    @PostMapping("/orders")
    public Map<String, Object> create(@Valid @RequestBody CreateOrderRequest body) {
        Long buyerId = UserContext.getUserId();
        if (buyerId == null) {
            return response(401, "请先登录", null);
        }
        try {
            TradeOrder order = tradeOrderService.create(buyerId,
                    body.itemId(), body.quantity());
            return response(200, "担保订单已创建", order);
        } catch (Exception e) {
            return response(400, e.getMessage(), null);
        }
    }

    @GetMapping("/orders")
    public Map<String, Object> list(@RequestParam(required = false) Integer page,
                                    @RequestParam(required = false) Integer pageSize,
                                    @RequestParam(required = false) String status,
                                    @RequestParam(required = false) String disputeStatus,
                                    @RequestParam(required = false) Long userId,
                                    @RequestParam(required = false) Long merchantId,
                                    @RequestParam(required = false)
                                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime fromTime,
                                    @RequestParam(required = false)
                                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime toTime) {
        Long currentUserId = UserContext.getUserId();
        if (currentUserId == null) {
            return response(401, "请先登录", null);
        }
        try {
            PageQuery pagination = PageQuery.of(page, pageSize, defaultPageSize, maxPageSize);
            TimeRangeQuery timeRange = new TimeRangeQuery(fromTime, toTime);
            timeRange.validate(92);
            requireOption("status", status, ORDER_STATUSES);
            requireOption("disputeStatus", disputeStatus, DISPUTE_STATUSES);
            boolean admin = merchantService.isAdmin(currentUserId);
            OrderListQuery query = new OrderListQuery(status, disputeStatus,
                    admin ? userId : null, admin ? merchantId : null, timeRange);
            PageResult<?> result = Timer.builder("trade_order_list_duration_seconds")
                    .register(meterRegistry)
                    .record(() -> tradeOrderService.listOrders(query, admin, currentUserId,
                            pagination.page(), pagination.pageSize()));
            return response(200, "success", result);
        } catch (IllegalArgumentException e) {
            return response(400, e.getMessage(), null);
        }
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
                                       @Valid @RequestBody DeliverOrderRequest body) {
        Long sellerId = UserContext.getUserId();
        if (sellerId == null) {
            return response(401, "请先登录", null);
        }
        try {
            return response(200, "交付成功", tradeOrderService.deliver(sellerId, orderNo, body.content()));
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
    public Map<String, Object> review(@PathVariable String orderNo,
                                      @Valid @RequestBody ReviewOrderRequest body) {
        Long buyerId = UserContext.getUserId();
        if (buyerId == null) {
            return response(401, "请先登录", null);
        }
        try {
            OrderReview review = tradeOrderService.review(buyerId, orderNo,
                    body.score(), body.content());
            return response(200, "评价成功", review);
        } catch (Exception e) {
            return response(400, e.getMessage(), null);
        }
    }

    @PostMapping("/orders/{orderNo}/evidence")
    public Map<String, Object> addEvidence(@PathVariable String orderNo,
                                           @Valid @RequestBody AddEvidenceRequest body) {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            return response(401, "请先登录", null);
        }
        try {
            DeliveryEvidence evidence = tradeOrderService.addEvidence(userId, orderNo,
                    body.evidenceType() == null ? "TEXT" : body.evidenceType(),
                    body.fileUrl(), body.content());
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
    public Map<String, Object> openDispute(@Valid @RequestBody OpenDisputeRequest body) {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            return response(401, "请先登录", null);
        }
        try {
            Dispute dispute = tradeOrderService.openDispute(userId, body.orderNo(),
                    body.disputeType(), body.reason(), body.refundAmount());
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
    public Map<String, Object> addMessage(@PathVariable String disputeNo,
                                          @Valid @RequestBody AddDisputeMessageRequest body) {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            return response(401, "请先登录", null);
        }
        try {
            return response(200, "留言成功",
                    tradeOrderService.addDisputeMessage(userId, disputeNo, body.message()));
        } catch (Exception e) {
            return response(400, e.getMessage(), null);
        }
    }

    @GetMapping("/admin/disputes/pending")
    public Map<String, Object> pendingDisputes(@RequestParam(required = false) Integer page,
                                               @RequestParam(required = false) Integer pageSize,
                                               @RequestParam(required = false) String status,
                                               @RequestParam(required = false) String orderNo,
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
            requireOption("status", status, DISPUTE_STATUSES);
            requireText("orderNo", orderNo, 64);
            DisputeListQuery query = new DisputeListQuery(status, orderNo, timeRange);
            PageResult<?> result = Timer.builder("trade_dispute_list_duration_seconds")
                    .register(meterRegistry)
                    .record(() -> tradeOrderService.listDisputes(query, pagination.page(), pagination.pageSize()));
            return response(200, "success", result);
        } catch (IllegalArgumentException e) {
            return response(400, e.getMessage(), null);
        }
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
    public Map<String, Object> arbitrate(@PathVariable String disputeNo,
                                         @Valid @RequestBody ArbitrateDisputeRequest body) {
        Long adminId = UserContext.getUserId();
        if (adminId == null || !merchantService.isAdmin(adminId)) {
            return response(403, "无管理员权限", null);
        }
        try {
            Arbitration arbitration = tradeOrderService.arbitrate(adminId, disputeNo,
                    body.result(), body.reason());
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

    private void requireOption(String name, String value, java.util.Set<String> allowed) {
        if (value != null && !allowed.contains(value)) {
            throw new IllegalArgumentException(name + " 不合法");
        }
    }

    private void requireText(String name, String value, int maxLength) {
        if (value != null && value.length() > maxLength) {
            throw new IllegalArgumentException(name + " 最长 " + maxLength + " 个字符");
        }
    }

    private static final java.util.Set<String> ORDER_STATUSES = java.util.Set.of(
            "CREATE_PENDING", "WAIT_PAY", "PAY_CONFIRMING", "PAID", "DELIVERED", "CONFIRMED",
            "SETTLING", "SETTLED", "REFUNDING", "REFUNDED", "CANCELLING", "CANCELLED", "CLOSED", "REJECTED");
    private static final java.util.Set<String> DISPUTE_STATUSES = java.util.Set.of(
            "NONE", "OPEN", "NEGOTIATING", "ARBITRATING", "RESOLVED", "APPEALED");
}
