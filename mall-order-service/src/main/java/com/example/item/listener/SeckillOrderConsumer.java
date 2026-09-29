package com.example.item.listener;


import com.example.item.entity.Order;
import com.example.item.mapper.OrderMapper;
import jakarta.annotation.Resource;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.apache.rocketmq.spring.core.RocketMQPushConsumerLifecycleListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
@RocketMQMessageListener(topic = "seckill-order-topic", consumerGroup = "seckill-order-consumer-group")
public class SeckillOrderConsumer implements RocketMQListener<String>, RocketMQPushConsumerLifecycleListener {

    @Resource
    private OrderMapper orderMapper;

    @Override
    public void prepareStart(DefaultMQPushConsumer consumer) {
        consumer.setMqClientApiTimeout(10_000);
    }

    @Override
    public void onMessage(String message) {
        System.out.println("订单服务收到秒杀消息，准备创建订单: " + message);

        // 1. 解析消息 payload (我们在 Item 服务发的是 "userId=xx&itemId=xx&orderNo=xx")
        String[] parts = message.split("&");
        Long userId = Long.valueOf(parts[0].split("=")[1]);
        Long itemId = Long.valueOf(parts[1].split("=")[1]);
        String orderNo = parts[2].split("=")[1];

        // 2. 组装订单实体
        Order order = new Order();
        order.setOrderNo(orderNo);
        order.setUserId(userId);
        order.setItemId(itemId);
        order.setPayAmount(new BigDecimal("99.00")); // 假设秒杀价固定 99.00，实际应查商品服务
        order.setStatus(0); // 0-新建（待支付）

        // 3. 真正入库，【简历核心亮点：唯一索引兜底】
        try {
            orderMapper.insert(order);
            System.out.println("✅ 订单创建成功！订单号: " + orderNo);
        } catch (DuplicateKeyException e) {
            // 利用我们在第一关设计的唯一索引 uk_order_no，就算 MQ 消息重发，也能拦截重复创单！
            System.out.println("️ 检测到重复消息，唯一索引拦截，忽略该消息。订单号: " + orderNo);
        } catch (Exception e) {
            System.err.println(" 订单创建异常: " + e.getMessage());
            // 如果是因为网络等其他异常，抛出异常让 MQ 重试
            throw e;
        }
    }
}
