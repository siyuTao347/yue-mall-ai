package com.example.item.service;

import com.example.item.mapper.TradeOrderMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class OrderRiskStateWriter {
    private final TradeOrderMapper orderMapper;

    public OrderRiskStateWriter(TradeOrderMapper orderMapper) {
        this.orderMapper = orderMapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void updateRiskStatus(String orderNo, String riskStatus, String riskLevel,
                                 String decisionNo, String reason) {
        orderMapper.updateRiskStatus(orderNo, riskStatus, riskLevel, decisionNo, reason, LocalDateTime.now());
    }
}
