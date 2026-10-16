package com.example.agent.customer.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class MemoryModels {
    private MemoryModels() {}

    public enum MemoryType {
        CATEGORY_PREFERENCE,
        DELIVERY_PREFERENCE,
        PLATFORM_PREFERENCE,
        SPEC_PREFERENCE,
        PRICE_RANGE,
        EXCLUSION
    }

    public enum Polarity {
        PREFER,
        EXCLUDE
    }

    public enum EvidenceKind {
        EXPLICIT_USER_STATEMENT,
        CONFIRMED_SELECTION
    }

    public enum MemoryStatus {
        ACTIVE,
        SUPERSEDED,
        EXPIRED,
        DELETED
    }

    public enum OutboxType {
        CAPTURE_TURN,
        PURGE_RECORD,
        PURGE_SCOPE
    }

    public enum OutboxState {
        PENDING,
        PROCESSING,
        SUCCEEDED,
        RETRY_WAIT,
        DEAD,
        SKIPPED
    }

    public enum OperationType {
        UPDATE_SETTINGS,
        PATCH_MEMORY,
        DELETE_MEMORY,
        CLEAR_ALL
    }

    public enum PurgeState {
        NONE,
        PENDING,
        COMPLETED
    }

    public enum RecallStatus {
        SKIPPED,
        HIT,
        EMPTY,
        DEGRADED
    }

    public record MemorySetting(
        long userId,
        String subjectKey,
        boolean readEnabled,
        boolean writeEnabled,
        long epoch,
        long revision,
        String policyVersion,
        Instant enabledAt,
        long captureAfterSequence,
        long nextSourceSequence
    ) {}

    public record MemoryHead(
        long id,
        long userId,
        String preferenceKey,
        String logicalId,
        String currentMemoryNo,
        int currentVersion,
        long epoch,
        long lastSourceSequence,
        long blockedThroughSequence,
        boolean deleted,
        long rowVersion
    ) {}

    public record MemoryRecord(
        long id,
        String memoryNo,
        String logicalId,
        int version,
        long userId,
        String subjectKey,
        long epoch,
        MemoryType memoryType,
        String preferenceKey,
        JsonNode scopeJson,
        JsonNode valueJson,
        Polarity polarity,
        String canonicalText,
        String sourceEventId,
        String sourceMessageNo,
        String sourceSessionNo,
        String sourceRunNo,
        long sourceSequence,
        String evidenceHash,
        String evidenceText,
        EvidenceKind evidenceKind,
        double confidence,
        int confirmationCount,
        String schemaVersion,
        String extractorVersion,
        MemoryStatus status,
        Instant observedAt,
        Instant lastConfirmedAt,
        Instant expiresAt,
        String supersededBy,
        Instant deletedAt
    ) {
        public static MemoryRecord of(String memoryNo, MemoryType type, Polarity polarity, String preferenceKey, String canonicalText, JsonNode scopeJson, JsonNode valueJson) {
            return new MemoryRecord(1L, memoryNo, "log_" + memoryNo, 1, 100L, "sub_100", 1L, type, preferenceKey, scopeJson, valueJson, polarity, canonicalText, "evt_1", "msg_1", "ses_1", "run_1", 1L, "hash", "quote", EvidenceKind.EXPLICIT_USER_STATEMENT, 0.95, 1, "v1", "ext_v1", MemoryStatus.ACTIVE, Instant.now(), Instant.now(), null, null, null);
        }
    }

    public record OutboxTask(
        long id,
        String eventId,
        OutboxType eventType,
        String dedupeKey,
        long userId,
        String subjectKey,
        long epoch,
        long sourceSequence,
        JsonNode payloadJson,
        OutboxState state,
        int attemptCount,
        Instant nextAttemptAt,
        String leaseOwner,
        long leaseGeneration,
        Instant leaseUntil,
        String lastErrorCode,
        String lastErrorMessage,
        String traceId
    ) {}

    public record MemoryScopeBarrier(
        long id,
        long userId,
        String normalizedScopeKey,
        JsonNode scopeJson,
        long blockedThroughSequence
    ) {}

    public record ExtractionCandidate(
        String operation,
        MemoryType type,
        JsonNode scope,
        JsonNode value,
        Polarity polarity,
        String durability,
        String quote,
        double confidence
    ) {}

    public record ValidatedCandidate(
        MemoryType type,
        String preferenceKey,
        JsonNode scope,
        JsonNode value,
        Polarity polarity,
        String canonicalText,
        String quote,
        double confidence
    ) {}

    public record MemorySnapshot(
        long userId,
        long epoch,
        long revision,
        RecallStatus status,
        List<MemoryRecord> memories,
        Map<String, List<MemoryRecord>> slicedMemoriesByIntent,
        String summaryText,
        long latencyMs
    ) {}

    public record RecallRequest(
        String question,
        JsonNode intents,
        JsonNode contextEntities,
        boolean recallAllowed
    ) {}
}
