package com.example.agent.rag.embed;

import com.example.agent.config.RagProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OpenAiCompatibleEmbeddingClientTest {

    private final RagProperties.Embedding props = new RagProperties.Embedding();
    private final OpenAiCompatibleEmbeddingClient client;

    {
        props.setApiKey("test-key");
        client = new OpenAiCompatibleEmbeddingClient(props, new ObjectMapper());
    }

    @Test
    @DisplayName("OpenAI 兼容模式缺少 API Key 时启动失败")
    void rejectsMissingApiKey() {
        RagProperties.Embedding missingKey = new RagProperties.Embedding();

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> new OpenAiCompatibleEmbeddingClient(missingKey, new ObjectMapper()));

        assertEquals("RAG_EMBEDDING_API_KEY is required when rag.embedding.provider=OPENAI_COMPATIBLE",
                exception.getMessage());
    }

    @Test
    @DisplayName("返回向量维度与配置一致并按 index 归位")
    void parsesVectorsByIndex() {
        String response = """
                {"data":[
                  {"index":1,"embedding":[0.3,0.1]},
                  {"index":0,"embedding":[0.1,0.2]}
                ]}
                """;
        props.setDim(2);

        var vectors = client.parse(response, 2);

        assertEquals(2, vectors.size());
        assertEquals(0.4472136d, vectors.get(0)[0], 1e-6);
        assertEquals(0.9486833d, vectors.get(1)[0], 1e-6);
    }

    @Test
    @DisplayName("返回维度不一致时拒绝入库")
    void rejectsDimensionMismatch() {
        props.setDim(3);

        EmbeddingException exception = assertThrows(EmbeddingException.class,
                () -> client.parse("{\"data\":[{\"index\":0,\"embedding\":[0.1,0.2]}]}", 1));

        assertEquals("Embedding 维度不匹配: expected=3, actual=2", exception.getMessage());
    }
}
