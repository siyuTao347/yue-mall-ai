package com.example.item.controller;

import api.audit.AuditLog;
import api.audit.BusinessType;
import api.audit.DeliveryMode;
import api.context.UserContext;
import com.example.item.service.OrderServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.ThreadLocalRandom;

@RestController
@RequestMapping("/api/order")
public class TccOrderController {

    @Autowired
    private OrderServiceImpl orderService;

    /**
     * TCC 压测/测试接口
     * 请求示例: http://localhost:8083/api/order/createTcc?itemId=1&count=1&mockException=false
     */
    @GetMapping("/createTcc")
    @AuditLog(title = "创建TCC订单", businessType = BusinessType.INSERT, deliveryMode = DeliveryMode.ASYNC_MQ)
    public String createTcc(@RequestParam("itemId") Long itemId,
                            @RequestParam("count") Integer count,
                            @RequestParam(value = "mockException", defaultValue = "false") boolean mockException,
                            @RequestParam(value = "userId", required = false) Long userId) {

        Long effectiveUserId = UserContext.getUserId();
        if (effectiveUserId == null) {
            effectiveUserId = (userId != null) ? userId : ThreadLocalRandom.current().nextLong(1000, 9999);
        }

        try {
            long startTime = System.currentTimeMillis();
            String orderNo = orderService.createTccOrder(effectiveUserId, itemId, count, mockException);
            long costTime = System.currentTimeMillis() - startTime;
            return "TCC 事务提交成功 | 耗时:" + costTime + "ms | 订单号: " + orderNo;
        } catch (Exception e) {
            return "TCC 事务回滚 | 失败原因: " + e.getMessage();
        }
    }
}