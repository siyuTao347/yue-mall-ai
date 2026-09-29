package com.example.item.service;

import api.risk.RiskCommandDTO;
import com.example.item.mapper.TradeOrderMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OrderRiskCommandHandlerTest {
    private TradeOrderMapper orderMapper;
    private OrderRiskCommandHandler handler;

    @BeforeEach
    void setUp() {
        orderMapper = mock(TradeOrderMapper.class);
        handler = new OrderRiskCommandHandler(orderMapper);
    }

    @Test
    void delaySettlementUsesProvidedHourAndConditionalSql() {
        when(orderMapper.delaySettlement(eq("SO1"), any(LocalDateTime.class),
                any(LocalDateTime.class))).thenReturn(1);

        Assertions.assertTrue(handler.handle(command(1)));

        ArgumentCaptor<LocalDateTime> payoutAt = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> operatedAt = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(orderMapper).delaySettlement(eq("SO1"), payoutAt.capture(), operatedAt.capture());
        Assertions.assertEquals(1L, Duration.between(operatedAt.getValue(), payoutAt.getValue())
                .toHours());
        Assertions.assertTrue(payoutAt.getValue().isAfter(operatedAt.getValue()));
    }

    @Test
    void delaySettlementRejectsHoursOutsideOneToSevenHundredTwenty() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> handler.handle(command(0)));
        Assertions.assertThrows(IllegalArgumentException.class, () -> handler.handle(command(721)));

        verifyNoInteractions(orderMapper);
    }

    private RiskCommandDTO command(int delayHours) {
        return RiskCommandDTO.builder()
                .commandNo("CMD1")
                .scene("ORDER")
                .orderNo("SO1")
                .command("DELAY_SETTLE")
                .actionParams(Map.of("delayHours", delayHours))
                .build();
    }
}
