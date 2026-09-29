package com.example.user.listener;

import com.example.user.service.PointService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.apache.rocketmq.spring.core.RocketMQPushConsumerLifecycleListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
@RocketMQMessageListener(topic = "order-paid-topic", consumerGroup = "point-reward-consumer-group")
public class OrderPaidPointConsumer implements RocketMQListener<String>, RocketMQPushConsumerLifecycleListener {

    @Autowired
    private PointService pointService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void prepareStart(DefaultMQPushConsumer consumer) {
        consumer.setMqClientApiTimeout(10_000);
    }

    @Override
    public void onMessage(String message) {
        log.info("收到订单支付成功事件消息，准备累积用户积分: {}", message);

        String orderNo = null;
        Long userId = null;
        BigDecimal payAmount = null;

        try {
            if (message.trim().startsWith("{")) {
                // 1. JSON 格式解析
                JsonNode root = objectMapper.readTree(message);
                orderNo = root.path("orderNo").asText();
                userId = root.path("userId").asLong();
                payAmount = new BigDecimal(root.path("payAmount").asText("0.00"));
            } else {
                // 2. QueryString 格式解析 (如: orderNo=xx&userId=xx&payAmount=xx)
                Map<String, String> map = new HashMap<>();
                String[] pairs = message.split("&");
                for (String pair : pairs) {
                    String[] kv = pair.split("=");
                    if (kv.length == 2) {
                        map.put(kv[0].trim(), kv[1].trim());
                    }
                }
                orderNo = map.get("orderNo");
                userId = Long.valueOf(map.get("userId"));
                payAmount = new BigDecimal(map.getOrDefault("payAmount", "0.00"));
            }

            if (orderNo == null || userId == null) {
                log.warn("消息内容缺少关键参数，忽略: {}", message);
                return;
            }

            // 执行积分发放
            pointService.rewardPointsForOrder(orderNo, userId, payAmount);

        } catch (Exception e) {
            log.error("处理订单支付积分消费异常: message={}, err={}", message, e.getMessage(), e);
            throw new RuntimeException("处理积分发放失败，触发 MQ 重试", e);
        }
    }
}
