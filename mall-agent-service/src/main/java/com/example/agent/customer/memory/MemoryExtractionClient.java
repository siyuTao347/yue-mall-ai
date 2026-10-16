package com.example.agent.customer.memory;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.example.agent.customer.CustomerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import static com.example.agent.customer.CustomerJson.error;

@Component
public class MemoryExtractionClient {
    private static final Logger log = LoggerFactory.getLogger(MemoryExtractionClient.class);
    private final CustomerProperties props;
    private final MemoryProperties memoryProps;
    private final ChatClient chatClient;
    private final Semaphore concurrencySemaphore;

    public MemoryExtractionClient(CustomerProperties props, MemoryProperties memoryProps) {
        this.props = props;
        this.memoryProps = memoryProps;
        this.concurrencySemaphore = new Semaphore(memoryProps.getExtractor().getMaxConcurrency());

        if ("MOCK".equals(props.getProvider()) || props.getApiKey() == null || props.getApiKey().isBlank()) {
            this.chatClient = null;
        } else {
            this.chatClient = createIsolatedChatClient();
        }
    }

    private ChatClient createIsolatedChatClient() {
        var httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();
        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofMillis(memoryProps.getExtractor().getTimeoutMs()));

        String normalizedBase = props.getBaseUrl() == null ? "" : props.getBaseUrl().strip().replaceAll("/+$", "");
        String root = normalizedBase.endsWith("/api/v1") ? normalizedBase.substring(0, normalizedBase.length() - 7) : normalizedBase;

        var api = DashScopeApi.builder()
            .baseUrl(root)
            .apiKey(props.getApiKey())
            .restClientBuilder(RestClient.builder().requestFactory(requestFactory))
            .build();

        var options = DashScopeChatOptions.builder()
            .withModel(props.getResponseModel())
            .multiModel(true)
            .enableThinking(false)
            .withTemperature(0.0)
            .withMaxToken(800)
            .build();

        var chatModel = DashScopeChatModel.builder()
            .dashScopeApi(api)
            .defaultOptions(options)
            .retryTemplate(RetryTemplate.builder().maxAttempts(1).fixedBackoff(1).build())
            .build();

        return ChatClient.create(chatModel);
    }

    public boolean isMock() {
        return chatClient == null;
    }

    public String callExtraction(String systemPrompt, String userMessage, MemoryDeadline deadline) {
        if (!concurrencySemaphore.tryAcquire()) {
            throw error(503, "MEMORY_EXTRACTOR_BUSY", "抽取模型并发达到上限");
        }
        try {
            int remaining = Math.min(deadline.remainingMs(), memoryProps.getExtractor().getTimeoutMs());
            if (chatClient == null) {
                // Mock extraction for testing/mock mode
                return mockExtract(userMessage);
            }
            return chatClient.prompt()
                .system(systemPrompt)
                .user(userMessage)
                .call()
                .content();
        } finally {
            concurrencySemaphore.release();
        }
    }

    private String mockExtract(String userMessage) {
        if (userMessage == null) return "{\"schemaVersion\":\"preference-extraction-v1\",\"candidates\":[]}";
        if (userMessage.contains("自动发货")) {
            return """
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
                      "evidence": {"quote": "自动发货"},
                      "confidence": 0.95
                    }
                  ]
                }
                """;
        } else if (userMessage.contains("人工发货")) {
            return """
                {
                  "schemaVersion": "preference-extraction-v1",
                  "candidates": [
                    {
                      "operation": "UPSERT",
                      "type": "DELIVERY_PREFERENCE",
                      "scope": {"beneficiary": "SELF", "categoryCode": "GAME_TOPUP"},
                      "value": {"deliveryMode": "MANUAL"},
                      "polarity": "PREFER",
                      "durability": "HABITUAL",
                      "evidence": {"quote": "人工发货"},
                      "confidence": 0.95
                    }
                  ]
                }
                """;
        }
        return "{\"schemaVersion\":\"preference-extraction-v1\",\"candidates\":[]}";
    }
}
