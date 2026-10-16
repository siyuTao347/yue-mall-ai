package com.example.agent.customer.memory;

import com.example.agent.customer.memory.MemoryModels.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PreferenceExtractorTest {
    private MemoryExtractionClient extractionClient;
    private PreferenceExtractor extractor;

    @BeforeEach
    void setUp() {
        extractionClient = mock(MemoryExtractionClient.class);
        extractor = new PreferenceExtractor(extractionClient);
    }

    @Test
    void fastRuleFilterBypassesNonPreferenceMessages() {
        var deadline = new MemoryDeadline(500);
        var result1 = extractor.extract("你好，在吗？", deadline);
        assertTrue(result1.isEmpty());

        var result2 = extractor.extract("催单，什么时候发货？", deadline);
        assertTrue(result2.isEmpty());

        var result3 = extractor.extract("我要申请退款", deadline);
        assertTrue(result3.isEmpty());

        verifyNoInteractions(extractionClient);
    }

    @Test
    void extractsValidPreferenceWithExactQuote() {
        String userMessage = "我平时只买自动发货的游戏充值卡，别给我人工发货的";
        String mockModelJson = """
            {
              "schemaVersion": "preference-extraction-v1",
              "candidates": [
                {
                  "operation": "UPSERT",
                  "type": "DELIVERY_PREFERENCE",
                  "scope": {"beneficiary": "SELF", "categoryCode": "GAME_TOPUP"},
                  "value": {"deliveryMode": "AUTO"},
                  "polarity": "PREFER",
                  "durability": "HABITUAL",
                  "evidence": {"quote": "我平时只买自动发货的游戏充值卡"},
                  "confidence": 0.98
                }
              ]
            }
            """;

        when(extractionClient.callExtraction(anyString(), anyString(), any())).thenReturn(mockModelJson);

        List<ValidatedCandidate> candidates = extractor.extract(userMessage, new MemoryDeadline(1000));
        assertEquals(1, candidates.size());
        ValidatedCandidate c = candidates.get(0);
        assertEquals(MemoryType.DELIVERY_PREFERENCE, c.type());
        assertEquals("DELIVERY|GAME_TOPUP|AUTO", c.preferenceKey());
        assertEquals(Polarity.PREFER, c.polarity());
        assertTrue(c.canonicalText().contains("自动发货"));
        assertEquals("我平时只买自动发货的游戏充值卡", c.quote());
    }

    @Test
    void rejectsHallucinatedQuoteNotInUserMessage() {
        String userMessage = "我平时喜欢买无糖可乐";
        String mockModelJson = """
            {
              "schemaVersion": "preference-extraction-v1",
              "candidates": [
                {
                  "operation": "UPSERT",
                  "type": "CATEGORY_PREFERENCE",
                  "scope": {"beneficiary": "SELF", "categoryCode": "DRINK"},
                  "value": {"categoryCode": "DRINK"},
                  "polarity": "PREFER",
                  "durability": "HABITUAL",
                  "evidence": {"quote": "我天天喝百事无糖可乐并且每次买两箱"},
                  "confidence": 0.95
                }
              ]
            }
            """;

        when(extractionClient.callExtraction(anyString(), anyString(), any())).thenReturn(mockModelJson);

        List<ValidatedCandidate> candidates = extractor.extract(userMessage, new MemoryDeadline(1000));
        assertTrue(candidates.isEmpty(), "Candidate with quote not in message must be rejected");
    }

    @Test
    void rejectsThirdPartyAndTemporaryDurability() {
        String userMessage = "平时帮朋友买的话我选顺丰，这次我自己想要便宜一点的";
        String mockModelJson = """
            {
              "schemaVersion": "preference-extraction-v1",
              "candidates": [
                {
                  "operation": "UPSERT",
                  "type": "DELIVERY_PREFERENCE",
                  "scope": {"beneficiary": "FRIEND", "categoryCode": "GLOBAL"},
                  "value": {"deliveryMode": "SF"},
                  "polarity": "PREFER",
                  "durability": "HABITUAL",
                  "evidence": {"quote": "平时帮朋友买的话我选顺丰"},
                  "confidence": 0.9
                },
                {
                  "operation": "UPSERT",
                  "type": "PRICE_RANGE",
                  "scope": {"beneficiary": "SELF", "categoryCode": "GLOBAL"},
                  "value": {"max": 50},
                  "polarity": "PREFER",
                  "durability": "TRANSIENT",
                  "evidence": {"quote": "这次我自己想要便宜一点的"},
                  "confidence": 0.8
                }
              ]
            }
            """;

        when(extractionClient.callExtraction(anyString(), anyString(), any())).thenReturn(mockModelJson);

        List<ValidatedCandidate> candidates = extractor.extract(userMessage, new MemoryDeadline(1000));
        assertTrue(candidates.isEmpty(), "Neither FRIEND beneficiary nor TRANSIENT durability may be extracted");
    }
}
