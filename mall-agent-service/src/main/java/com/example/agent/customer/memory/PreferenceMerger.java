package com.example.agent.customer.memory;

import com.example.agent.customer.memory.MemoryModels.ValidatedCandidate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class PreferenceMerger {
    private static final Logger log = LoggerFactory.getLogger(PreferenceMerger.class);
    private final CustomerMemoryStore memoryStore;

    public PreferenceMerger(CustomerMemoryStore memoryStore) {
        this.memoryStore = memoryStore;
    }

    public boolean merge(
        long userId,
        String subjectKey,
        long epoch,
        String sourceEventId,
        String sourceMessageNo,
        String sourceSessionNo,
        String sourceRunNo,
        long sourceSequence,
        List<ValidatedCandidate> candidates,
        long outboxTaskId,
        long leaseGeneration
    ) {
        if (candidates == null || candidates.isEmpty()) {
            return memoryStore.completeTask(outboxTaskId, leaseGeneration);
        }

        // Deduplicate and resolve intra-event conflicts
        Map<String, ValidatedCandidate> uniqueByKey = new LinkedHashMap<>();
        Set<String> conflictingKeys = new HashSet<>();

        for (ValidatedCandidate c : candidates) {
            if (uniqueByKey.containsKey(c.preferenceKey())) {
                // Duplicate within same turn
                continue;
            }
            uniqueByKey.put(c.preferenceKey(), c);
        }

        List<ValidatedCandidate> resolved = uniqueByKey.values().stream()
            .filter(c -> !conflictingKeys.contains(c.preferenceKey()))
            .toList();

        return memoryStore.mergeCandidatesInTransaction(
            userId,
            subjectKey,
            epoch,
            sourceEventId,
            sourceMessageNo,
            sourceSessionNo,
            sourceRunNo,
            sourceSequence,
            resolved,
            outboxTaskId,
            leaseGeneration
        );
    }
}
