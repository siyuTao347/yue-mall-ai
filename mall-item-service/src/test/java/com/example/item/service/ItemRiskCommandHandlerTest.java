package com.example.item.service;

import api.risk.RiskCommandDTO;
import com.example.item.mapper.ItemMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ItemRiskCommandHandlerTest {
    private ItemMapper itemMapper;
    private ItemRiskCommandHandler handler;

    @BeforeEach
    void setUp() {
        itemMapper = mock(ItemMapper.class);
        handler = new ItemRiskCommandHandler(itemMapper);
    }

    @Test
    void freezeIsIdempotentWhenConditionalUpdateSkipsDuplicate() {
        RiskCommandDTO command = command();
        when(itemMapper.freezeByRisk(1L, "HIGH", "DECISION1", "高风险商品"))
                .thenReturn(1, 0);

        Assertions.assertTrue(handler.handle(command));
        Assertions.assertFalse(handler.handle(command));

        verify(itemMapper, times(2)).freezeByRisk(1L, "HIGH", "DECISION1", "高风险商品");
    }

    private RiskCommandDTO command() {
        return RiskCommandDTO.builder()
                .commandNo("CMD1")
                .scene("LISTING")
                .itemId(1L)
                .command("FREEZE")
                .decisionNo("DECISION1")
                .reason("高风险商品")
                .build();
    }
}
