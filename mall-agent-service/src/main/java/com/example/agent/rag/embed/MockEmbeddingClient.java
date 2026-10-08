package com.example.agent.rag.embed;

import com.example.agent.rag.TextTokenizer;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Mock 确定性向量：同一文本必得同一向量，用于本地开发与单元测试。
 * <p><b>不具备真实语义泛化能力</b>，召回质量仅代表链路是否连通，不能用于效果评估结论。</p>
 */
public class MockEmbeddingClient implements EmbeddingClient {

    private final String model;
    private final int dim;

    public MockEmbeddingClient(String model, int dim) {
        if (dim <= 0) {
            throw new IllegalArgumentException("向量维度必须大于 0");
        }
        this.model = model == null || model.isBlank() ? "mock-hash-v1" : model;
        this.dim = dim;
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        List<float[]> vectors = new ArrayList<>(texts.size());
        for (String text : texts) {
            vectors.add(embedOne(text));
        }
        return vectors;
    }

    float[] embedOne(String text) {
        float[] vector = new float[dim];
        if (text == null || text.isEmpty()) {
            return vector;
        }
        for (String token : TextTokenizer.tokens(text)) {
            int primary = fnv1a32(token);
            vector[Math.floorMod(primary, dim)] += 1.0f;
            vector[Math.floorMod(primary * 31 + 7, dim)] += 0.5f;
        }
        return VectorFormat.l2Normalize(vector);
    }

    static int fnv1a32(String value) {
        int hash = 0x811C9DC5;
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            hash ^= (b & 0xFF);
            hash *= 0x01000193;
        }
        return hash;
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public int dim() {
        return dim;
    }
}
