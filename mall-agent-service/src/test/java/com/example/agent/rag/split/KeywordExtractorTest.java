package com.example.agent.rag.split;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class KeywordExtractorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("提取型号、业务词与中文 2-gram")
    void extractKeywords() throws Exception {
        String json = KeywordExtractor.extractJson("AK-47 | 红线 · 商品介绍/7. 价格与费用",
                "平台服务费按订单金额的 2% 计收，最低 0.01 元，在结算冷却期结束后扣除。", 32);
        JsonNode node = MAPPER.readTree(json);
        assertTrue(node.has("ak-47"), "型号应被识别为关键词");
        // 中文按 2-gram 切分，因此命中的是「服务」「务费」而不是 3 字词
        assertTrue(node.has("服务") || node.has("务费"), "中文 2-gram 应被识别");
        assertTrue(node.size() <= 32);
    }

    @Test
    @DisplayName("标题命中权重高于正文")
    void titleWeightsMore() throws Exception {
        String json = KeywordExtractor.extractJson("红线", "红线", 32);
        JsonNode node = MAPPER.readTree(json);
        assertTrue(node.get("红线").asDouble() > 1.0, "标题权重应为正文字符的 5 倍");
    }
}
