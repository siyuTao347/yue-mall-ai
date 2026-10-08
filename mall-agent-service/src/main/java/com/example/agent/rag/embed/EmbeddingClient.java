package com.example.agent.rag.embed;

import java.util.List;

/** Embedding 客户端抽象：Mock 与 OpenAI 兼容实现可互换，检索链路只依赖此接口。 */
public interface EmbeddingClient {

    /**
     * 批量向量化，返回顺序与入参一致。
     *
     * @throws EmbeddingException 模型不可用或返回结构非法
     */
    List<float[]> embed(List<String> texts);

    String model();

    int dim();
}
