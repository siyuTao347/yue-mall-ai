package com.example.agent.customer.memory;

import com.example.agent.customer.memory.MemoryModels.*;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
public class CustomerMemoryWorker {
    private static final Logger log = LoggerFactory.getLogger(CustomerMemoryWorker.class);

    private final MemoryProperties properties;
    private final CustomerMemoryStore memoryStore;
    private final PreferenceExtractor extractor;
    private final PreferenceMerger merger;
    private final ScheduledExecutorService scheduler;
    private final String workerId;
    private volatile boolean running = false;

    public CustomerMemoryWorker(
        MemoryProperties properties,
        CustomerMemoryStore memoryStore,
        PreferenceExtractor extractor,
        PreferenceMerger merger
    ) {
        this.properties = properties;
        this.memoryStore = memoryStore;
        this.extractor = extractor;
        this.merger = merger;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "customer-memory-worker");
            t.setDaemon(true);
            return t;
        });

        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "worker";
        }
        this.workerId = host + "_" + UUID.randomUUID().toString().substring(0, 8);
    }

    @PostConstruct
    public void start() {
        if (!properties.isEnabled() || !properties.getWorker().isCaptureEnabled()) {
            log.info("CustomerMemoryWorker is disabled by configuration");
            return;
        }
        running = true;
        scheduler.scheduleWithFixedDelay(this::pollAndProcess, 1000, properties.getWorker().getPollIntervalMs(), TimeUnit.MILLISECONDS);
        log.info("CustomerMemoryWorker started with workerId={}", workerId);
    }

    @PreDestroy
    public void stop() {
        running = false;
        scheduler.shutdownNow();
    }

    public void pollAndProcess() {
        if (!running) return;
        try {
            List<OutboxTask> tasks = memoryStore.claimTasks(
                properties.getWorker().getClaimBatchSize(),
                workerId,
                properties.getWorker().getLeaseSeconds()
            );
            if (tasks.isEmpty()) {
                return;
            }
            log.debug("CustomerMemoryWorker claimed {} outbox tasks", tasks.size());
            for (OutboxTask task : tasks) {
                processSingleTask(task);
            }
        } catch (Exception e) {
            log.error("CustomerMemoryWorker polling error: {}", e.getMessage(), e);
        }
    }

    private void processSingleTask(OutboxTask task) {
        try {
            JsonNode payload = task.payloadJson();
            String userMessage = payload.path("userMessage").asText("");
            String messageNo = payload.path("sourceMessageNo").asText("");
            String sessionNo = payload.path("sourceSessionNo").asText("");
            String runNo = payload.path("sourceRunNo").asText("");

            MemoryDeadline deadline = new MemoryDeadline(properties.getExtractor().getTimeoutMs());

            // 1. Extract candidates outside DB transaction
            List<ValidatedCandidate> candidates = extractor.extract(userMessage, deadline);

            // 2. Merge candidates inside short DB transaction with CAS check
            merger.merge(
                task.userId(),
                task.subjectKey(),
                task.epoch(),
                task.eventId(),
                messageNo,
                sessionNo,
                runNo,
                task.sourceSequence(),
                candidates,
                task.id(),
                task.leaseGeneration()
            );
        } catch (Exception e) {
            log.warn("Failed processing outbox task {}: {}", task.id(), e.getMessage());
            memoryStore.failTask(task.id(), task.leaseGeneration(), "TASK_PROCESS_FAILED", e.getMessage(), true, task.attemptCount());
        }
    }
}
