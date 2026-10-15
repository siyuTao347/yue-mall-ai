package com.example.item.controller;

import api.context.UserContext;
import com.example.item.entity.Order;
import com.example.item.service.OrderServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/order")
public class OrderController {

    @Autowired
    private OrderServiceImpl orderService;

    /**
     * 支付订单接口 (支付成功后异步广播 order-paid-topic 累积积分)
     */
    @PostMapping("/pay")
    public Map<String, Object> payOrder(@RequestParam("orderNo") String orderNo) {
        Map<String, Object> resp = new HashMap<>();
        try {
            Long currentUserId = UserContext.getUserId();
            Order order = orderService.getOrderByNo(orderNo);
            if (order == null) {
                resp.put("code", 404);
                resp.put("msg", "订单不存在: " + orderNo);
                return resp;
            }
            if (currentUserId != null && !order.getUserId().equals(currentUserId)) {
                resp.put("code", 403);
                resp.put("msg", "无权支付他人订单");
                return resp;
            }
            boolean success = orderService.payOrder(orderNo);
            resp.put("code", 200);
            resp.put("msg", success ? "支付成功，已触发积分累积" : "支付失败");
            resp.put("orderNo", orderNo);
            return resp;
        } catch (Exception e) {
            resp.put("code", 500);
            resp.put("msg", "支付异常: " + e.getMessage());
            return resp;
        }
    }

    /**
     * 查询订单详情 (秒杀排队轮询此接口确认是否创单完成)
     */
    @GetMapping("/detail")
    public Map<String, Object> getOrderDetail(@RequestParam("orderNo") String orderNo) {
        Order order = orderService.getOrderByNo(orderNo);
        Map<String, Object> resp = new HashMap<>();
        if (order == null) {
            resp.put("code", 404);
            resp.put("msg", "订单排队处理中或不存在");
            return resp;
        }
        Long currentUserId = UserContext.getUserId();
        if (currentUserId != null && !order.getUserId().equals(currentUserId)) {
            resp.put("code", 403);
            resp.put("msg", "无权查看他人订单");
            return resp;
        }
        resp.put("code", 200);
        resp.put("msg", "success");
        resp.put("data", order);
        return resp;
    }

    /**
     * 查询用户历史订单
     */
    @GetMapping("/list")
    public Map<String, Object> getUserOrders(@RequestParam(value = "userId", required = false) Long userId) {
        Map<String, Object> resp = new HashMap<>();
        Long effectiveUserId = UserContext.getUserId();
        if (effectiveUserId == null) {
            effectiveUserId = userId;
        }
        if (effectiveUserId == null) {
            resp.put("code", 401);
            resp.put("msg", "未登录或缺少用户上下文");
            resp.put("data", List.of());
            return resp;
        }
        List<Order> list = orderService.getUserOrders(effectiveUserId);
        resp.put("code", 200);
        resp.put("msg", "success");
        resp.put("data", list);
        return resp;
    }
}
