package com.example.user.controller;

import api.util.JwtUtil;
import com.example.user.entity.PointRecord;
import com.example.user.entity.UserPoint;
import com.example.user.service.PointService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/user/point")
@CrossOrigin(origins = "*")
public class PointController {

    @Autowired
    private PointService pointService;

    /**
     * 查询用户积分账户概况
     */
    @GetMapping("/summary")
    public Map<String, Object> getPointSummary(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestParam(value = "userId", required = false) Long userId) {
        Long effectiveUserId = JwtUtil.parseUserId(authHeader);
        if (effectiveUserId == null) {
            effectiveUserId = (userId != null) ? userId : 1L;
        }
        UserPoint point = pointService.getUserPointSummary(effectiveUserId);
        Map<String, Object> resp = new HashMap<>();
        resp.put("code", 200);
        resp.put("msg", "success");
        resp.put("data", point);
        return resp;
    }

    /**
     * 查询用户积分流水变动记录
     */
    @GetMapping("/records")
    public Map<String, Object> getPointRecords(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestParam(value = "userId", required = false) Long userId,
            @RequestParam(value = "limit", defaultValue = "20") Integer limit) {
        Long effectiveUserId = JwtUtil.parseUserId(authHeader);
        if (effectiveUserId == null) {
            effectiveUserId = (userId != null) ? userId : 1L;
        }
        List<PointRecord> list = pointService.getPointRecords(effectiveUserId, limit);
        Map<String, Object> resp = new HashMap<>();
        resp.put("code", 200);
        resp.put("msg", "success");
        resp.put("data", list);
        return resp;
    }

    /**
     * 手动触发/测试发放消费积分 (用于无需经过完整支付网关时的直接测试)
     */
    @PostMapping("/mockReward")
    public Map<String, Object> mockReward(
            @RequestParam("orderNo") String orderNo,
            @RequestParam("userId") Long userId,
            @RequestParam("payAmount") BigDecimal payAmount) {
        boolean ok = pointService.rewardPointsForOrder(orderNo, userId, payAmount);
        UserPoint point = pointService.getUserPointSummary(userId);
        Map<String, Object> resp = new HashMap<>();
        resp.put("code", 200);
        resp.put("msg", ok ? "积分发放成功" : "积分发放未执行");
        resp.put("currentPoints", point.getTotalPoints());
        return resp;
    }
}
