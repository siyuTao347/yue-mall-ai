package com.example.item.service;

import com.example.item.entity.TradeOrderStatusLog;
import com.example.item.mapper.TradeOrderStatusLogMapper;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class TradeStatusLogService {
    private final TradeOrderStatusLogMapper mapper;

    public TradeStatusLogService(TradeOrderStatusLogMapper mapper) {
        this.mapper = mapper;
    }

    public void log(String orderNo, String from, String to, String type,
                    String operatorType, Long operatorId, String reason) {
        TradeOrderStatusLog record = new TradeOrderStatusLog();
        record.setOrderNo(orderNo);
        record.setFromStatus(from);
        record.setToStatus(to);
        record.setStatusType(type);
        record.setOperatorType(operatorType);
        record.setOperatorId(operatorId);
        record.setReason(reason);
        record.setCreatedTime(LocalDateTime.now());
        mapper.insert(record);
    }

    public List<TradeOrderStatusLog> listByOrderNo(String orderNo) {
        return mapper.selectList(new LambdaQueryWrapper<TradeOrderStatusLog>()
                .eq(TradeOrderStatusLog::getOrderNo, orderNo)
                .orderByAsc(TradeOrderStatusLog::getId));
    }
}
