package com.example.agent.customer.memory;

import com.example.agent.customer.memory.MemoryModels.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.example.agent.customer.CustomerJson.object;
import static org.junit.jupiter.api.Assertions.*;

class StructuredPreferenceSelectorTest {
    private StructuredPreferenceSelector selector;

    @BeforeEach
    void setUp() {
        selector = new StructuredPreferenceSelector();
    }

    private MemoryRecord createRecord(String no, MemoryType type, Polarity polarity, String key, String canonical, String deliveryMode) {
        return MemoryRecord.of(
            no,
            type,
            polarity,
            key,
            canonical,
            object().put("categoryCode", "GAME_TOPUP"),
            deliveryMode != null ? object().put("deliveryMode", deliveryMode) : object()
        );
    }

    @Test
    void thirdPartyTurnSuppressesHabitualPreferences() {
        var r1 = createRecord("M1", MemoryType.DELIVERY_PREFERENCE, Polarity.PREFER, "DELIVERY|GAME_TOPUP|AUTO", "偏好自动发货", "AUTO");
        var result = selector.selectApplicableMemories(
            List.of(r1),
            "这次是帮朋友买充值卡，怎么发货无所谓",
            null,
            null,
            5
        );
        assertTrue(result.isEmpty(), "Third-party purchase statement must suppress all habitual preferences");
    }

    @Test
    void currentTurnConditionOverridesConflictingHistoricalPreference() {
        var r1 = createRecord("M1", MemoryType.DELIVERY_PREFERENCE, Polarity.PREFER, "DELIVERY|GAME_TOPUP|AUTO", "偏好自动发货", "AUTO");
        var result = selector.selectApplicableMemories(
            List.of(r1),
            "请帮我找人工发货的商品",
            null,
            null,
            5
        );
        assertTrue(result.isEmpty(), "Explicit current turn preference for manual delivery must override historical auto delivery preference");
    }

    @Test
    void applicablePreferenceSelectedWhenNoConflict() {
        var r1 = createRecord("M1", MemoryType.DELIVERY_PREFERENCE, Polarity.PREFER, "DELIVERY|GAME_TOPUP|AUTO", "偏好自动发货", "AUTO");
        var result = selector.selectApplicableMemories(
            List.of(r1),
            "有什么推荐的Steam点卡吗？",
            null,
            null,
            5
        );
        assertEquals(1, result.size());
        assertEquals("M1", result.get(0).memoryNo());
    }

    @Test
    void exclusionPreferenceAlwaysPrioritized() {
        var r1 = createRecord("M1", MemoryType.EXCLUSION, Polarity.EXCLUDE, "EXCLUSION|GLOBAL|ITEM_A", "排除商品A", null);
        var r2 = createRecord("M2", MemoryType.DELIVERY_PREFERENCE, Polarity.PREFER, "DELIVERY|GAME_TOPUP|AUTO", "偏好自动发货", "AUTO");
        var result = selector.selectApplicableMemories(
            List.of(r1, r2),
            "推荐点充值卡",
            null,
            null,
            5
        );
        assertEquals(2, result.size());
        assertEquals("M1", result.get(0).memoryNo());
    }
}
