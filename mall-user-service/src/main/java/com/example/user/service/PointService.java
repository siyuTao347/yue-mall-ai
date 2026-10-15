package com.example.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.user.entity.PointRecord;
import com.example.user.entity.UserPoint;
import com.example.user.mapper.PointRecordMapper;
import com.example.user.mapper.UserPointMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
public class PointService {

    @Autowired
    private UserPointMapper userPointMapper;

    @Autowired
    private PointRecordMapper pointRecordMapper;

    @Autowired
    private StringRedisTemplate redisTemplate;

    /**
     * 消费订单发放积分 (双重幂等机制: Redis SETNX 前置拦截 + 数据库唯一索引兜底)
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean rewardPointsForOrder(String orderNo, Long userId, BigDecimal payAmount) {
        if (payAmount == null || payAmount.compareTo(BigDecimal.ZERO) <= 0) {
            log.info("订单金额为空或<=0，不发放积分: orderNo={}", orderNo);
            return true;
        }

        // 1. 积分规则: 1 元 = 1 积分 (向下取整)
        int earnedPoints = payAmount.intValue();
        if (earnedPoints <= 0) {
            return true;
        }

        // 2. 第一道防线: Redis 轻量级防重过滤器 (拦截瞬时网络重发)
        String dedupKey = "point:dedup:" + orderNo;
        Boolean isFirst = redisTemplate.opsForValue().setIfAbsent(dedupKey, "1", Duration.ofHours(24));
        if (Boolean.FALSE.equals(isFirst)) {
            log.warn("⚠️ 检测到重复消费事件 (Redis幂等拦截), 订单号: {}", orderNo);
            return true;
        }

        try {
            // 3. 检查是否已经存在该订单的积分流水（业务事实幂等）
            LambdaQueryWrapper<PointRecord> checkWrapper = new LambdaQueryWrapper<>();
            checkWrapper.eq(PointRecord::getOrderNo, orderNo)
                    .eq(PointRecord::getChangeType, 1);
            PointRecord existingRecord = pointRecordMapper.selectOne(checkWrapper);
            if (existingRecord != null) {
                log.warn("⚠️ 数据库流水已存在该订单的积分记录, 订单号: {}, 幂等安全返回", orderNo);
                return true;
            }

            // 4. 确保用户积分账户存在
            UserPoint userPoint = userPointMapper.selectById(userId);
            if (userPoint == null) {
                userPoint = new UserPoint(userId, 0, 0, 0, LocalDateTime.now(), LocalDateTime.now());
                try {
                    userPointMapper.insert(userPoint);
                } catch (DuplicateKeyException ignored) {
                    userPoint = userPointMapper.selectById(userId);
                }
            }

            int currentBalance = userPoint != null ? userPoint.getTotalPoints() : 0;
            int estimatedBalanceAfter = currentBalance + earnedPoints;

            // 5. SHOP-06: 先插入积分明细流水凭据 (利用唯一键 uk_order_type 拦截并发竞争与重放)
            // 必须在修改余额之前插入；若唯一冲突，直接在此处捕获并忽略，绝不会脏改用户余额！
            PointRecord record = new PointRecord();
            record.setUserId(userId);
            record.setOrderNo(orderNo);
            record.setChangePoints(earnedPoints);
            record.setBalanceAfter(estimatedBalanceAfter);
            record.setChangeType(1); // 1-消费返利
            record.setRemark("订单 " + orderNo + " 消费 ￥" + payAmount + " 获得 " + earnedPoints + " 积分");
            record.setCreateTime(LocalDateTime.now());
            pointRecordMapper.insert(record);

            // 6. 流水落库成功后，再原子累加用户积分余额
            userPointMapper.addPoints(userId, earnedPoints);

            // 7. 更新最新真实余额
            UserPoint updatedPoint = userPointMapper.selectById(userId);
            int realBalanceAfter = updatedPoint != null ? updatedPoint.getTotalPoints() : estimatedBalanceAfter;
            if (realBalanceAfter != estimatedBalanceAfter) {
                record.setBalanceAfter(realBalanceAfter);
                pointRecordMapper.updateById(record);
            }

            log.info("✅ 成功为用户 {} 发放消费积分: +{} pts (余额: {} pts), 订单号: {}",
                    userId, earnedPoints, realBalanceAfter, orderNo);
            return true;
        } catch (DuplicateKeyException e) {
            log.warn("⚠️ 数据库唯一索引拦截重复加积分事件, 订单号: {}, 此时未修改用户余额，已安全忽略", orderNo);
            return true;
        } catch (Exception e) {
            // 发生未知异常，清理 Redis 幂等标记以便 MQ 重试
            redisTemplate.delete(dedupKey);
            log.error("❌ 积分发放异常: orderNo={}, userId={}, err={}", orderNo, userId, e.getMessage(), e);
            throw e;
        }
    }

    /**
     * 获取用户积分账户概要
     */
    public UserPoint getUserPointSummary(Long userId) {
        UserPoint point = userPointMapper.selectById(userId);
        if (point == null) {
            point = new UserPoint(userId, 0, 0, 0, LocalDateTime.now(), LocalDateTime.now());
            try {
                userPointMapper.insert(point);
            } catch (Exception ignored) {
                point = userPointMapper.selectById(userId);
            }
        }
        return point;
    }

    /**
     * 查询用户积分明细列表
     */
    public List<PointRecord> getPointRecords(Long userId, int limit) {
        LambdaQueryWrapper<PointRecord> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PointRecord::getUserId, userId)
                .orderByDesc(PointRecord::getCreateTime)
                .last("LIMIT " + Math.min(limit, 100));
        return pointRecordMapper.selectList(wrapper);
    }
}
