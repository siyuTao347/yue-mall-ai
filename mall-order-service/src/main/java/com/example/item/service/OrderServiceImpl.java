package com.example.item.service;

import api.ItemDubboService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.item.entity.Order;
import com.example.item.mapper.OrderMapper;
import io.seata.spring.annotation.GlobalTransactional;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Slf4j
@Service
public class OrderServiceImpl {
    @DubboReference(timeout = 5000, retries = 0, check = false)
    private ItemDubboService itemDubboService;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired(required = false)
    private RocketMQTemplate rocketMQTemplate;

    @GlobalTransactional(name = "tcc-create-order-tx", rollbackFor = Exception.class)
    public String createTccOrder(Long userId, Long itemId, Integer count, boolean mockException) {
        log.info("====== 开启 Seata 全局事务 ======");

        // 1. RPC 调用商品服务：TCC Try 阶段 (预留库存)
        boolean trySuccess = itemDubboService.prepareDeductStock(itemId, count);
        if (!trySuccess) {
            throw new RuntimeException("商品服务预扣库存 (Try) 失败");
        }

        // 2. 本地事务：创建订单
        Order order = new Order();
        String orderNo = "TCC" + System.currentTimeMillis() + userId;
        order.setOrderNo(orderNo);
        order.setUserId(userId);
        order.setItemId(itemId);
        order.setPayAmount(new BigDecimal("99.00"));
        order.setStatus(0);

        orderMapper.insert(order);
        log.info("本地订单创建成功，订单号: {}", orderNo);

        // 3. 【用于测试 Cancel 回滚】如果传入 mockException=true，主动抛出异常
        if (mockException) {
            log.error("模拟下游发生异常，触发 Seata 全局回滚！");
            throw new RuntimeException("模拟业务异常，触发 TCC Cancel 回滚预留库存");
        }

        // 方法正常结束，Seata 会自动去调用商品服务的 Commit 方法
        return orderNo;
    }

    /**
     * 支付订单并发送异步支付成功领域事件 (触发积分累积)
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean payOrder(String orderNo) {
        LambdaQueryWrapper<Order> query = new LambdaQueryWrapper<>();
        query.eq(Order::getOrderNo, orderNo);
        Order order = orderMapper.selectOne(query);
        if (order == null) {
            throw new RuntimeException("订单不存在: " + orderNo);
        }

        if (order.getStatus() == 1) {
            log.info("订单已处于支付状态，无需重复支付: {}", orderNo);
            return true;
        }

        // 1. 更新订单为已支付状态 (status = 1)
        LambdaUpdateWrapper<Order> update = new LambdaUpdateWrapper<>();
        update.eq(Order::getOrderNo, orderNo).set(Order::getStatus, 1);
        int rows = orderMapper.update(null, update);

        if (rows > 0) {
            log.info("✅ 订单 {} 支付成功，准备异步广播 order-paid-topic 积分事件", orderNo);
            // 2. 发送 RocketMQ 支付成功消息 (解耦积分服务)
            if (rocketMQTemplate != null) {
                try {
                    String payload = "orderNo=" + orderNo +
                            "&userId=" + order.getUserId() +
                            "&payAmount=" + order.getPayAmount();
                    rocketMQTemplate.convertAndSend("order-paid-topic", payload);
                    log.info("RocketMQ 订单支付事件投递成功: {}", payload);
                } catch (Exception e) {
                    log.error("投递 order-paid-topic 消息失败: {}", e.getMessage(), e);
                }
            }
            return true;
        }
        return false;
    }

    /**
     * 查询订单详情
     */
    public Order getOrderByNo(String orderNo) {
        LambdaQueryWrapper<Order> query = new LambdaQueryWrapper<>();
        query.eq(Order::getOrderNo, orderNo);
        return orderMapper.selectOne(query);
    }

    /**
     * 查询用户订单列表
     */
    public List<Order> getUserOrders(Long userId) {
        LambdaQueryWrapper<Order> query = new LambdaQueryWrapper<>();
        query.eq(Order::getUserId, userId).orderByDesc(Order::getCreateTime).last("LIMIT 50");
        return orderMapper.selectList(query);
    }
}
