package com.example.agent.customer.memory;

import com.example.agent.customer.memory.MemoryModels.*;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static com.example.agent.customer.CustomerJson.object;
import static org.junit.jupiter.api.Assertions.*;

class MemoryQueryEnricherTest {
    private final MemoryQueryEnricher enricher = new MemoryQueryEnricher();

    @Test
    void enrichKnowledgeQueryAppendsPreferencesToPrompt() {
        String baseQuery = "推荐一些Steam游戏点卡";
        var r1 = MemoryRecord.of(
            "M1",
            MemoryType.DELIVERY_PREFERENCE,
            Polarity.PREFER,
            "DELIVERY|GLOBAL|AUTO",
            "偏好自动发货",
            object(),
            object().put("deliveryMode", "AUTO")
        );

        String enriched = enricher.enrichKnowledgeQuery(baseQuery, List.of(r1));
        assertEquals("推荐一些Steam游戏点卡 自动发货", enriched);
    }

    @Test
    void emptyMemoriesLeavesBaseQueryUntouched() {
        String baseQuery = "查询商品信息";
        String enriched = enricher.enrichKnowledgeQuery(baseQuery, Collections.emptyList());
        assertEquals(baseQuery, enriched);
    }
}
