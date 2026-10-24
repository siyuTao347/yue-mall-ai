package com.example.agent.rag.embed;

import com.example.agent.config.RagProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容 Embedding 客户端（{@code POST {base-url}/v1/embeddings}）。
 * <p>任何异常统一包装为 {@link EmbeddingException}，由上层决定重试或降级。</p>
 */
public class OpenAiCompatibleEmbeddingClient implements EmbeddingClient {

    private final RagProperties.Embedding props;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public OpenAiCompatibleEmbeddingClient(RagProperties.Embedding props, ObjectMapper objectMapper) {
        this(props, objectMapper, createRestClient(props));
    }

    OpenAiCompatibleEmbeddingClient(RagProperties.Embedding props, ObjectMapper objectMapper, RestClient restClient) {
        if (props.getBatchSize() <= 0) {
            throw new IllegalArgumentException("rag.embedding.batch-size must be positive");
        }
        this.props = props;
        this.objectMapper = objectMapper;
        this.restClient = restClient;
    }

    private static RestClient createRestClient(RagProperties.Embedding props) {
        if (props.getApiKey() == null || props.getApiKey().isBlank()) {
            throw new IllegalArgumentException(
                    "RAG_EMBEDDING_API_KEY is required when rag.embedding.provider=OPENAI_COMPATIBLE");
        }
        RestClient.Builder builder = RestClient.builder()
                .baseUrl(props.getBaseUrl())
                .requestFactory(requestFactory(props.getTimeoutMs()));
        if (props.getApiKey() != null && !props.getApiKey().isBlank()) {
            builder.defaultHeader("Authorization", "Bearer " + props.getApiKey());
        }
        return builder.build();
    }

    private static SimpleClientHttpRequestFactory requestFactory(int timeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(timeoutMs));
        factory.setReadTimeout(Duration.ofMillis(timeoutMs));
        return factory;
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        return embedWithUsage(texts).vectors();
    }

    @Override
    public BatchResult embedWithUsage(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return new BatchResult(List.of(), 0);
        }
        List<float[]> vectors = new ArrayList<>(texts.size());
        int tokens = 0;
        boolean allUsageKnown = true;
        // Callers may submit larger logical batches; provider limits apply to each HTTP request.
        for (int from = 0; from < texts.size(); from += props.getBatchSize()) {
            BatchResult part = requestBatch(texts.subList(from, Math.min(from + props.getBatchSize(), texts.size())));
            vectors.addAll(part.vectors());
            if (part.tokens() == null) allUsageKnown = false;
            else tokens += part.tokens();
        }
        return new BatchResult(List.copyOf(vectors), allUsageKnown ? Integer.valueOf(tokens) : null);
    }

    private BatchResult requestBatch(List<String> texts) {
        Map<String, Object> body = Map.of(
                "model", props.getModel(),
                "input", texts,
                "dimensions", props.getDim()
        );
        String response;
        try {
            response = restClient.post()
                    .uri("/v1/embeddings")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
        } catch (Exception e) {
            throw new EmbeddingException("调用 Embedding 服务失败: " + e.getMessage(), e);
        }
        List<float[]> vectors=parse(response, texts.size());
        Integer tokens=null;
        try {
            JsonNode usage=objectMapper.readTree(response).path("usage").path("total_tokens");
            if(usage.canConvertToInt())tokens=usage.asInt();
        } catch(JsonProcessingException e) {
            throw new EmbeddingException("解析 Embedding usage 失败", e);
        }
        return new BatchResult(vectors,tokens);
    }

    /** 按返回的 index 归位，避免服务端乱序导致向量与文本错配。 */
    List<float[]> parse(String response, int expected) {
        JsonNode root;
        try {
            root = objectMapper.readTree(response);
        } catch (JsonProcessingException e) {
            throw new EmbeddingException("解析 Embedding 响应失败", e);
        }
        JsonNode data = root.path("data");
        if (!data.isArray() || data.size() != expected) {
            throw new EmbeddingException("Embedding 返回条数不匹配: expected=%d, actual=%d"
                    .formatted(expected, data.size()));
        }
        float[][] buffer = new float[expected][];
        for (JsonNode item : data) {
            int index = item.path("index").asInt(-1);
            if (index < 0 || index >= expected) {
                throw new EmbeddingException("Embedding 返回 index 非法: " + index);
            }
            List<Double> values = new ArrayList<>();
            for (JsonNode value : item.path("embedding")) {
                values.add(value.asDouble());
            }
            if (values.size() != props.getDim()) {
                throw new EmbeddingException("Embedding 维度不匹配: expected=%d, actual=%d"
                        .formatted(props.getDim(), values.size()));
            }
            buffer[index] = VectorFormat.l2Normalize(VectorFormat.fromNumbers(values));
        }
        for (int i = 0; i < expected; i++) {
            if (buffer[i] == null) {
                throw new EmbeddingException("Embedding 返回缺少 index=" + i);
            }
        }
        return Arrays.asList(buffer);
    }

    @Override
    public String model() {
        return props.getModel();
    }

    @Override
    public int dim() {
        return props.getDim();
    }
}
