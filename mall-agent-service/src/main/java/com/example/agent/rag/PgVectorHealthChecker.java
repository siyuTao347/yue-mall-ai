package com.example.agent.rag;

import com.example.agent.config.RagProperties;
import com.example.agent.rag.embed.EmbeddingClient;
import com.example.agent.rag.store.RagHealthState;
import com.example.agent.rag.store.VectorStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动自检：校验向量维度配置一致性并探活 pgvector。
 * <p>自检失败不阻断启动，只把检索能力标记为不可用，避免影响业务主链路。</p>
 */
@Slf4j
@Component
public class PgVectorHealthChecker implements ApplicationRunner {

    private final VectorStore vectorStore;
    private final EmbeddingClient embeddingClient;
    private final RagProperties ragProperties;
    private final RagHealthState healthState;

    public PgVectorHealthChecker(VectorStore vectorStore, EmbeddingClient embeddingClient,
                                 RagProperties ragProperties, RagHealthState healthState) {
        this.vectorStore = vectorStore;
        this.embeddingClient = embeddingClient;
        this.ragProperties = ragProperties;
        this.healthState = healthState;
    }

    @Override
    public void run(ApplicationArguments args) {
        int clientDim = embeddingClient.dim();
        int configuredDim = ragProperties.getPgvector().getEmbeddingDim();
        if (clientDim != configuredDim) {
            log.error("[RAG] 向量维度配置不一致，向量入库将失败: embedding.dim={}, pgvector.embedding-dim={}",
                    clientDim, configuredDim);
            healthState.markVectorAvailable(false, null);
            return;
        }
        String version = vectorStore.extensionVersion();
        if (version == null) {
            log.error("[RAG] pgvector 不可用，检索能力降级；请检查容器 0444598a7276 与扩展是否创建");
            healthState.markVectorAvailable(false, null);
            return;
        }
        healthState.markVectorAvailable(true, version);
        log.info("[RAG] pgvector 就绪: extensionVersion={}, embeddingModel={}, dim={}",
                version, embeddingClient.model(), clientDim);
    }
}
