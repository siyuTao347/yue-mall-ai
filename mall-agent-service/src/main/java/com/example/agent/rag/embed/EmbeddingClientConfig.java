package com.example.agent.rag.embed;

import com.example.agent.config.RagProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Embedding 客户端装配。
 * <p>provider=OPENAI_COMPATIBLE 但未配置 base-url 时降级为 Mock，保证本地无模型环境仍可跑通全流程。</p>
 */
@Slf4j
@Configuration
public class EmbeddingClientConfig {

    @Bean
    public EmbeddingClient embeddingClient(RagProperties ragProperties, ObjectMapper objectMapper) {
        RagProperties.Embedding embedding = ragProperties.getEmbedding();
        if ("OPENAI_COMPATIBLE".equalsIgnoreCase(embedding.getProvider())) {
            if (embedding.getBaseUrl() == null || embedding.getBaseUrl().isBlank()) {
                log.warn("[RAG] provider=OPENAI_COMPATIBLE 但 rag.embedding.base-url 为空，降级为 Mock 向量");
            } else {
                log.info("[RAG] 使用 OpenAI 兼容 Embedding: model={}, dim={}, baseUrl={}",
                        embedding.getModel(), embedding.getDim(), embedding.getBaseUrl());
                return new OpenAiCompatibleEmbeddingClient(embedding, objectMapper);
            }
        }
        log.info("[RAG] 使用 Mock 确定性 Embedding: model={}, dim={}",
                embedding.getModel(), embedding.getDim());
        return new MockEmbeddingClient(embedding.getModel(), embedding.getDim());
    }
}
