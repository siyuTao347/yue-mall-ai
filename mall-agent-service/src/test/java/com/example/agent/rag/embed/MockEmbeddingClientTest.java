package com.example.agent.rag.embed;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MockEmbeddingClientTest {

    private final MockEmbeddingClient client = new MockEmbeddingClient("mock-hash-v1", 1536);

    @Test
    @DisplayName("同一文本必须得到相同向量（确定性）")
    void deterministic() {
        float[] first = client.embed(List.of("AK-47 | 红线 平台服务费 2%")).get(0);
        float[] second = client.embed(List.of("AK-47 | 红线 平台服务费 2%")).get(0);
        assertEquals(first.length, second.length);
        for (int i = 0; i < first.length; i++) {
            assertEquals(first[i], second[i], 1e-9, "第 " + i + " 维不一致");
        }
    }

    @Test
    @DisplayName("向量已归一化，模长为 1")
    void normalized() {
        float[] vector = client.embed(List.of("虚拟资产担保交易平台")).get(0);
        double norm = 0;
        for (float value : vector) {
            norm += (double) value * value;
        }
        assertEquals(1.0d, Math.sqrt(norm), 1e-5);
    }

    @Test
    @DisplayName("维度与配置一致")
    void dimension() {
        assertEquals(1536, client.dim());
        assertEquals(1536, client.embed(List.of("测试")).get(0).length);
    }

    @Test
    @DisplayName("相同文本相似度高于不相关文本")
    void similarityOrdering() {
        float[] base = client.embed(List.of("平台服务费按订单金额的 2% 计收")).get(0);
        float[] similar = client.embed(List.of("平台服务费按订单金额的 2% 计收，最低 0.01 元")).get(0);
        float[] unrelated = client.embed(List.of("和平精英满级账号 支持改绑")).get(0);
        // 哈希向量对完全无共同 token 的文本是正交的（相似度 0），这也是一种可用信号
        assertTrue(cosine(base, similar) > cosine(base, unrelated));
        assertTrue(cosine(base, unrelated) >= 0);
    }

    private double cosine(float[] a, float[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            sum += (double) a[i] * b[i];
        }
        return sum;
    }
}
