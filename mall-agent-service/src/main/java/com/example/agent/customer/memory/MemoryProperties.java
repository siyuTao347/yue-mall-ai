package com.example.agent.customer.memory;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties("customer-service.memory")
public class MemoryProperties {
    private boolean enabled = true;
    private boolean readEnabled = true;
    private boolean writeEnabled = true;
    private String retrievalMode = "STRUCTURED";
    private int recallTimeoutMs = 800;
    private int releaseCheckTimeoutMs = 200;
    private int recallMaxConcurrency = 4;
    private int answerReserveMs = 15000;
    private int maxActiveMemoriesPerUser = 100;
    private int injectLimit = 5;
    private int contextMaxTokens = 600;
    private int contextMaxChars = 1200;
    private int captureMaxChars = 3000;
    private int captureMaxCandidates = 5;
    private int captureExpireHours = 24;

    private Extractor extractor = new Extractor();
    private Worker worker = new Worker();
    private ReadStore readStore = new ReadStore();

    @Data
    public static class Extractor {
        private String modelSource = "CUSTOMER_SPECIALIST";
        private int timeoutMs = 10000;
        private int maxConcurrency = 2;
        private String schemaVersion = "preference-extraction-v1";
    }

    @Data
    public static class Worker {
        private boolean captureEnabled = true;
        private boolean cleanupEnabled = true;
        private int dbConcurrency = 2;
        private int claimBatchSize = 10;
        private int leaseSeconds = 30;
        private long pollIntervalMs = 2000;
    }

    @Data
    public static class ReadStore {
        private int maxPoolSize = 2;
        private int connectionTimeoutMs = 250;
        private int socketTimeoutMs = 500;
    }
}
