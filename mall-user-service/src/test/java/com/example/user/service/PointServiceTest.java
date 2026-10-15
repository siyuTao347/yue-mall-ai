package com.example.user.service;

import com.example.user.entity.PointRecord;
import com.example.user.entity.UserPoint;
import com.example.user.mapper.PointRecordMapper;
import com.example.user.mapper.UserPointMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Duration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PointServiceTest {
    private UserPointMapper userPointMapper;
    private PointRecordMapper pointRecordMapper;
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private PointService pointService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        userPointMapper = mock(UserPointMapper.class);
        pointRecordMapper = mock(PointRecordMapper.class);
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        pointService = new PointService();
        ReflectionTestUtils.setField(pointService, "userPointMapper", userPointMapper);
        ReflectionTestUtils.setField(pointService, "pointRecordMapper", pointRecordMapper);
        ReflectionTestUtils.setField(pointService, "redisTemplate", redisTemplate);
    }

    @Test
    void rewardPointsAwardsCorrectlyWhenNewOrder() {
        when(pointRecordMapper.selectOne(any())).thenReturn(null);
        UserPoint userPoint = new UserPoint();
        userPoint.setUserId(100L);
        userPoint.setTotalPoints(50);
        when(userPointMapper.selectById(100L)).thenReturn(userPoint);

        boolean success = pointService.rewardPointsForOrder("ORD1001", 100L, new BigDecimal("99.00"));

        Assertions.assertTrue(success);
        verify(pointRecordMapper).insert(any(PointRecord.class));
        verify(userPointMapper).addPoints(100L, 99);
    }

    @Test
    void rewardPointsDoesNotAddPointsWhenDuplicateKeyOccursOnRecord() {
        when(pointRecordMapper.selectOne(any())).thenReturn(null);
        UserPoint userPoint = new UserPoint();
        userPoint.setUserId(100L);
        userPoint.setTotalPoints(50);
        when(userPointMapper.selectById(100L)).thenReturn(userPoint);
        when(pointRecordMapper.insert(any(PointRecord.class))).thenThrow(new DuplicateKeyException("uk_order_type"));

        boolean success = pointService.rewardPointsForOrder("ORD1001", 100L, new BigDecimal("99.00"));

        Assertions.assertTrue(success);
        // SHOP-06: 插入流水失败时不应该调用 addPoints!
        verify(userPointMapper, never()).addPoints(anyLong(), anyInt());
    }

    @Test
    void rewardPointsSkipsWhenDatabaseRecordAlreadyExists() {
        PointRecord existing = new PointRecord();
        existing.setOrderNo("ORD1001");
        when(pointRecordMapper.selectOne(any())).thenReturn(existing);

        boolean success = pointService.rewardPointsForOrder("ORD1001", 100L, new BigDecimal("99.00"));

        Assertions.assertTrue(success);
        verify(pointRecordMapper, never()).insert(any(PointRecord.class));
        verify(userPointMapper, never()).addPoints(anyLong(), anyInt());
    }
}
