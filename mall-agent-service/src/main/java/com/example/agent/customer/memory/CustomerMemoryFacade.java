package com.example.agent.customer.memory;

import api.agent.AgentActor;
import com.example.agent.customer.memory.MemoryModels.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.Semaphore;

@Service
public class CustomerMemoryFacade {
    private static final Logger log = LoggerFactory.getLogger(CustomerMemoryFacade.class);

    private final MemoryProperties properties;
    private final CustomerMemoryStore memoryStore;
    private final MemoryReadStore readStore;
    private final StructuredPreferenceSelector selector;
    private final Semaphore recallSemaphore;

    public CustomerMemoryFacade(
        MemoryProperties properties,
        CustomerMemoryStore memoryStore,
        MemoryReadStore readStore,
        StructuredPreferenceSelector selector
    ) {
        this.properties = properties;
        this.memoryStore = memoryStore;
        this.readStore = readStore;
        this.selector = selector;
        this.recallSemaphore = new Semaphore(properties.getRecallMaxConcurrency());
    }

    public MemorySnapshot recall(AgentActor actor, RecallRequest request, MemoryDeadline deadline) {
        if (!properties.isEnabled() || !properties.isReadEnabled() || !request.recallAllowed()) {
            return emptySnapshot(actor.userId(), RecallStatus.SKIPPED, "DISABLED_OR_NOT_ALLOWED");
        }

        if (!recallSemaphore.tryAcquire()) {
            log.warn("Memory recall semaphore exhausted, degrading recall for user {}", actor.userId());
            return emptySnapshot(actor.userId(), RecallStatus.DEGRADED, "SEMAPHORE_EXHAUSTED");
        }

        long start = System.nanoTime();
        try {
            deadline.remainingMs();
            MemorySetting setting = readStore.readSetting(actor.userId(), deadline);
            if (!setting.readEnabled()) {
                return emptySnapshot(actor.userId(), RecallStatus.SKIPPED, "USER_READ_DISABLED");
            }

            List<MemoryRecord> active = readStore.readActiveMemories(actor.userId(), setting.epoch(), properties.getMaxActiveMemoriesPerUser() + 1, deadline);
            if (active.size() > properties.getMaxActiveMemoriesPerUser()) {
                log.error("Invariant broken: User {} active memories exceed {} limit", actor.userId(), properties.getMaxActiveMemoriesPerUser());
                return emptySnapshot(actor.userId(), RecallStatus.DEGRADED, "CAPACITY_OVERFLOW");
            }

            if (active.isEmpty()) {
                return emptySnapshot(actor.userId(), RecallStatus.EMPTY, "NO_ACTIVE_MEMORIES");
            }

            Map<String, List<MemoryRecord>> sliced = new HashMap<>();
            List<MemoryRecord> allSelected = new ArrayList<>();

            if (request.intents() != null && request.intents().isArray()) {
                for (JsonNode intent : request.intents()) {
                    String instanceId = intent.path("intentInstanceId").asText();
                    List<MemoryRecord> applicable = selector.selectApplicableMemories(
                        active,
                        request.question(),
                        intent,
                        request.contextEntities(),
                        properties.getInjectLimit()
                    );
                    sliced.put(instanceId, applicable);
                    for (MemoryRecord r : applicable) {
                        if (!allSelected.contains(r)) {
                            allSelected.add(r);
                        }
                    }
                }
            } else {
                allSelected = selector.selectApplicableMemories(active, request.question(), null, request.contextEntities(), properties.getInjectLimit());
            }

            StringBuilder summary = new StringBuilder();
            for (MemoryRecord r : allSelected) {
                summary.append("- ").append(r.canonicalText()).append("\n");
            }

            long elapsed = (System.nanoTime() - start) / 1_000_000;
            return new MemorySnapshot(
                actor.userId(),
                setting.epoch(),
                setting.revision(),
                allSelected.isEmpty() ? RecallStatus.EMPTY : RecallStatus.HIT,
                allSelected,
                sliced,
                summary.toString().strip(),
                elapsed
            );
        } catch (Exception e) {
            log.warn("Memory recall failed gracefully: {}", e.getMessage());
            return emptySnapshot(actor.userId(), RecallStatus.DEGRADED, e.getMessage());
        } finally {
            recallSemaphore.release();
        }
    }

    public boolean revalidate(AgentActor actor, MemorySnapshot snapshot, MemoryDeadline deadline) {
        if (snapshot == null || snapshot.status() != RecallStatus.HIT) {
            return true;
        }
        try {
            deadline.remainingMs();
            MemorySetting current = readStore.readSetting(actor.userId(), deadline);
            if (!current.readEnabled() || current.epoch() != snapshot.epoch() || current.revision() != snapshot.revision()) {
                log.info("Revalidation failed: User {} memory setting changed (epoch {}->{}, rev {}->{})",
                    actor.userId(), snapshot.epoch(), current.epoch(), snapshot.revision(), current.revision());
                return false;
            }
            Instant now = Instant.now();
            for (MemoryRecord r : snapshot.memories()) {
                if (r.expiresAt() != null && r.expiresAt().isBefore(now)) {
                    log.info("Revalidation failed: Memory {} expired during response generation", r.memoryNo());
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            log.warn("Memory pre-send revalidation check failed: {}", e.getMessage());
            return false;
        }
    }

    public MemorySetting getSettings(AgentActor actor) {
        return memoryStore.getOrCreateSetting(actor.userId());
    }

    public MemorySetting updateSettings(AgentActor actor, boolean readEnabled, boolean writeEnabled, Long expectedRevision) {
        return memoryStore.updateSettings(actor.userId(), readEnabled, writeEnabled, expectedRevision);
    }

    public List<MemoryRecord> queryMemoriesForUser(AgentActor actor, int limit) {
        return memoryStore.queryAllActiveMemoriesForUser(actor.userId(), limit);
    }

    public boolean deleteMemory(AgentActor actor, String memoryNo, String clientOpId) {
        return memoryStore.deleteMemory(actor.userId(), memoryNo, clientOpId);
    }

    public boolean clearAllMemories(AgentActor actor, String clientOpId) {
        return memoryStore.clearAllMemories(actor.userId(), clientOpId);
    }

    public boolean patchMemory(AgentActor actor, String memoryNo, JsonNode valueJson, JsonNode scopeJson, Integer expectedVersion, String clientOpId) {
        return memoryStore.patchMemory(actor.userId(), memoryNo, valueJson, scopeJson, expectedVersion, clientOpId);
    }

    private static MemorySnapshot emptySnapshot(long userId, RecallStatus status, String reason) {
        return new MemorySnapshot(userId, 0, 0, status, Collections.emptyList(), Collections.emptyMap(), "", 0);
    }
}
