package com.example.agent.rag.embed;

import java.util.List;

/** Embedding 客户端抽象：Mock 与 OpenAI 兼容实现可互换，检索链路只依赖此接口。 */
public interface EmbeddingClient {

    record BatchResult(List<float[]> vectors, Integer tokens) {}

    /**
     * 批量向量化，返回顺序与入参一致。
     *
     * @throws EmbeddingException 模型不可用或返回结构非法
     */
    List<float[]> embed(List<String> texts);

    /** Batch embedding with optional provider token usage, when available. */
    default BatchResult embedWithUsage(List<String> texts) {
        return new BatchResult(embed(texts), null);
    }

    String model();

    int dim();
}
