package com.example.item.controller;

import api.audit.AuditLog;
import api.audit.BusinessType;
import api.audit.DeliveryMode;
import api.context.UserContext;
import com.example.item.service.OrderServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/order")
public class TccOrderController {

    @Autowired
    private OrderServiceImpl orderService;

    /**
     * TCC 压测/测试接口 (支持 POST / GET)
     */
    @RequestMapping(value = "/createTcc", method = {org.springframework.web.bind.annotation.RequestMethod.GET, org.springframework.web.bind.annotation.RequestMethod.POST})
    @AuditLog(title = "创建TCC订单", businessType = BusinessType.INSERT, deliveryMode = DeliveryMode.ASYNC_MQ)
    public String createTcc(@RequestParam(value = "itemId", required = false) Long itemId,
                            @RequestParam(value = "count", required = false, defaultValue = "1") Integer count,
                            @RequestParam(value = "mockException", defaultValue = "false") boolean mockException,
                            @RequestParam(value = "userId", required = false) Long userId) {

        if (itemId == null || itemId <= 0) {
            return "TCC 事务失败 | itemId 参数不合法: " + itemId;
        }
        if (count == null || count <= 0 || count > 10) {
            return "TCC 事务失败 | 购买数量 count 必须在 1~10 之间: " + count;
        }

        Long effectiveUserId = UserContext.getUserId();
        if (effectiveUserId == null) {
            effectiveUserId = userId;
        }
        if (effectiveUserId == null) {
            return "TCC 事务失败 | 未登录或缺少有效用户身份";
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