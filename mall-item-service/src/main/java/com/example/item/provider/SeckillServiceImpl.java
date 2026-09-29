package com.example.item.provider;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
public class SeckillServiceImpl {
    @Resource(name = "stringRedisTemplate")
    private StringRedisTemplate redisTemplate;

    @Resource
    private RocketMQTemplate rocketMQTemplate;

    private static final String SECKILL_LUA =
            "if (redis.call('exists', KEYS[1]) == 1) then " +
                    "    local stock = tonumber(redis.call('get', KEYS[1])); " +
                    "    if (stock > 0) then " +
                    "        redis.call('decr', KEYS[1]); " +
                    "        return 1; " +
                    "    end; " +
                    "end; " +
                    "return 0;";

    /**
     * 生成动态秒杀接口加密 Token (防刷与接口隐藏)
     */
    public String createPathToken(Long userId, Long itemId) {
        String token = UUID.randomUUID().toString().replace("-", "");
        String key = "seckill:path:" + userId + ":" + itemId;
        redisTemplate.opsForValue().set(key, token, Duration.ofSeconds(60));
        log.info("生成秒杀动态 Token: userId={}, itemId={}, token={}", userId, itemId, token);
        return token;
    }

    /**
     * 验证动态秒杀 Token
     */
    public boolean verifyPathToken(Long userId, Long itemId, String pathToken) {
        if (pathToken == null || pathToken.isEmpty()) {
            return false;
        }
        String key = "seckill:path:" + userId + ":" + itemId;
        String realToken = redisTemplate.opsForValue().get(key);
        return pathToken.equals(realToken);
    }

    /**
     * 重载秒杀方法 (带动态路径校验)
     */
    public Map<String, Object> doSeckillWithResult(Long userId, Long itemId, String pathToken) {
        Map<String, Object> resp = new HashMap<>();

        // 1. 验证动态 Token
        if (pathToken != null && !verifyPathToken(userId, itemId, pathToken)) {
            resp.put("code", 400);
            resp.put("msg", "非法请求，秒杀令牌无效或已过期！");
            return resp;
        }

        // 2. Redis 保证单用户单商品防重幂等
        String idempotencyKey = "seckill:idempotent:" + userId + ":" + itemId;
        Boolean isFirst = redisTemplate.opsForValue().setIfAbsent(idempotencyKey, "1", Duration.ofMinutes(5));
        if (Boolean.FALSE.equals(isFirst)) {
            resp.put("code", 429);
            resp.put("msg", "您已参与过抢购，请勿重复提交！");
            return resp;
        }

        // 3. Redis Lua 预扣库存
        String stockKey = "seckill:stock:" + itemId;
        DefaultRedisScript<Long> script = new DefaultRedisScript<>(SECKILL_LUA, Long.class);
        Long result = redisTemplate.execute(script, Collections.singletonList(stockKey));

        if (result == null || result == 0) {
            // 库存不足，清理幂等标记
            redisTemplate.delete(idempotencyKey);
            // 写入售罄屏障标记
            redisTemplate.opsForValue().set("seckill:soldout:" + itemId, "1", Duration.ofHours(2));
            resp.put("code", 410);
            resp.put("msg", "手慢了，商品已售罄！");
            return resp;
        }

        // 4. RocketMQ 事务半消息：预扣成功，发送半消息异步创单
        String transactionId = UUID.randomUUID().toString();
        String orderNo = "ORD" + System.currentTimeMillis() + userId;

        Message<String> message = MessageBuilder.withPayload("userId=" + userId + "&itemId=" + itemId + "&orderNo=" + orderNo)
                .setHeader("TRANSACTION_ID", transactionId)
                .setHeader("ORDER_NO", orderNo)
                .setHeader("ITEM_ID", itemId)
                .build();

        try {
            rocketMQTemplate.sendMessageInTransaction("seckill-order-topic", message, null);
            resp.put("code", 200);
            resp.put("msg", "抢购请求已受理，正在排队处理中");
            resp.put("orderNo", orderNo);
            return resp;
        } catch (Exception e) {
            // 发送消息失败，回滚 Redis 库存和幂等标记
            redisTemplate.opsForValue().increment(stockKey);
            redisTemplate.delete(idempotencyKey);
            log.error("发送秒杀事务消息失败，已回退库存: {}", e.getMessage(), e);
            resp.put("code", 500);
            resp.put("msg", "系统繁忙，抢购排队失败，请稍后重试");
            return resp;
        }
    }

    /**
     * 兼容原压测调用
     */
    public String doSeckill(Long userId, Long itemId) {
        Map<String, Object> res = doSeckillWithResult(userId, itemId, null);
        return (String) res.get("msg");
    }
}
