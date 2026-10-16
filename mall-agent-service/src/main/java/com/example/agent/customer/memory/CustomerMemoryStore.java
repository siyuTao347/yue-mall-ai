package com.example.agent.customer.memory;

import com.example.agent.customer.CustomerDatabase;
import com.example.agent.customer.memory.MemoryModels.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static com.example.agent.customer.CustomerJson.*;

@Repository
public class CustomerMemoryStore {
    private static final Logger log = LoggerFactory.getLogger(CustomerMemoryStore.class);
    private final CustomerDatabase db;

    public CustomerMemoryStore(CustomerDatabase db) {
        this.db = db;
    }

    // ----------------------------------------------------------------------------------
    // Setting Management
    // ----------------------------------------------------------------------------------

    public MemorySetting getOrCreateSetting(long userId) {
        var list = db.mysql.query("SELECT * FROM t_agent_memory_setting WHERE user_id = ?",
            (rs, rowNum) -> mapSetting(rs), userId);
        if (!list.isEmpty()) {
            return list.get(0);
        }
        String subjectKey = "u_" + UUID.randomUUID().toString().replace("-", "");
        try {
            db.mysql.update(
                "INSERT INTO t_agent_memory_setting(user_id, subject_key, read_enabled, write_enabled, epoch, revision, policy_version, next_source_sequence) " +
                    "VALUES (?, ?, 0, 0, 1, 1, 'v1', 1)",
                userId, subjectKey
            );
        } catch (DuplicateKeyException e) {
            // In case of concurrent creation
            return db.mysql.queryForObject("SELECT * FROM t_agent_memory_setting WHERE user_id = ?",
                (rs, rowNum) -> mapSetting(rs), userId);
        }
        return db.mysql.queryForObject("SELECT * FROM t_agent_memory_setting WHERE user_id = ?",
            (rs, rowNum) -> mapSetting(rs), userId);
    }

    public MemorySetting getSettingForUpdate(long userId) {
        return db.mysql.queryForObject(
            "SELECT * FROM t_agent_memory_setting WHERE user_id = ? FOR UPDATE",
            (rs, rowNum) -> mapSetting(rs), userId
        );
    }

    public long allocateNextSourceSequence(long userId) {
        // Called inside MySQL transaction
        MemorySetting setting = getSettingForUpdate(userId);
        long seq = setting.nextSourceSequence();
        db.mysql.update(
            "UPDATE t_agent_memory_setting SET next_source_sequence = next_source_sequence + 1, updated_time = now(3) WHERE user_id = ?",
            userId
        );
        return seq;
    }

    public MemorySetting updateSettings(long userId, boolean readEnabled, boolean writeEnabled, Long expectedRevision) {
        return db.tx.execute(status -> {
            MemorySetting setting = getSettingForUpdate(userId);
            if (expectedRevision != null && setting.revision() != expectedRevision) {
                throw error(409, "MEMORY_REVISION_CONFLICT", "设置版本冲突，请刷新后重试");
            }
            long newEpoch = setting.epoch();
            boolean wasEnabled = setting.readEnabled() || setting.writeEnabled();
            boolean nowEnabled = readEnabled || writeEnabled;
            // Turning off or changing authorization increments epoch to prevent old worker leakage
            if (wasEnabled && !nowEnabled) {
                newEpoch++;
            }
            long newRevision = setting.revision() + 1;
            Instant enabledAt = nowEnabled ? (setting.enabledAt() == null ? Instant.now() : setting.enabledAt()) : null;

            db.mysql.update(
                "UPDATE t_agent_memory_setting SET read_enabled = ?, write_enabled = ?, epoch = ?, revision = ?, " +
                    "enabled_at = ?, updated_time = now(3) WHERE user_id = ?",
                readEnabled ? 1 : 0, writeEnabled ? 1 : 0, newEpoch, newRevision,
                enabledAt != null ? Timestamp.from(enabledAt) : null, userId
            );

            audit(userId, null, null, null, "UPDATE", "SUCCESS",
                object().put("readEnabled", readEnabled).put("writeEnabled", writeEnabled)
                    .put("epoch", newEpoch).put("revision", newRevision),
                null, null, "USER");

            return getSettingForUpdate(userId);
        });
    }

    // ----------------------------------------------------------------------------------
    // Active Memory Retrieval (Bounded to <= 100)
    // ----------------------------------------------------------------------------------

    public List<MemoryRecord> queryActiveMemories(long userId, long epoch, int limit) {
        String sql = "SELECT r.* FROM t_agent_memory_head h " +
            "JOIN t_agent_memory_record r ON r.memory_no = h.current_memory_no " +
            "WHERE h.user_id = ? AND r.user_id = ? " +
            "AND h.deleted = 0 AND h.epoch = ? AND r.epoch = ? " +
            "AND r.version = h.current_version AND r.status = 'ACTIVE' " +
            "AND r.expires_at > now(3) " +
            "ORDER BY r.memory_no ASC LIMIT ?";

        return db.mysql.query(sql, (rs, rowNum) -> mapRecord(rs), userId, userId, epoch, epoch, limit);
    }

    public List<MemoryRecord> queryAllActiveMemoriesForUser(long userId, int limit) {
        MemorySetting setting = getOrCreateSetting(userId);
        return queryActiveMemories(userId, setting.epoch(), limit);
    }

    // ----------------------------------------------------------------------------------
    // Outbox Operations
    // ----------------------------------------------------------------------------------

    public void appendCaptureInCurrentTransaction(
        String eventId,
        String dedupeKey,
        long userId,
        String subjectKey,
        long epoch,
        long sourceSequence,
        JsonNode payloadJson,
        String traceId
    ) {
        db.mysql.update(
            "INSERT INTO t_agent_memory_outbox(event_id, event_type, dedupe_key, user_id, subject_key, epoch, " +
                "source_sequence, payload_json, state, trace_id, created_time, next_attempt_at) " +
                "VALUES (?, 'CAPTURE_TURN', ?, ?, ?, ?, ?, ?, 'PENDING', ?, now(3), now(3))",
            eventId, dedupeKey, userId, subjectKey, epoch, sourceSequence, write(payloadJson), traceId
        );
    }

    public List<OutboxTask> claimTasks(int batchSize, String leaseOwner, int leaseSeconds) {
        return db.tx.execute(tx -> {
            // Short transaction: Select with FOR UPDATE SKIP LOCKED
            var tasks = db.mysql.query(
                "SELECT * FROM t_agent_memory_outbox " +
                    "WHERE state IN ('PENDING', 'RETRY_WAIT') AND next_attempt_at <= now(3) " +
                    "ORDER BY id ASC LIMIT ? FOR UPDATE SKIP LOCKED",
                (rs, rowNum) -> mapOutbox(rs), batchSize
            );
            if (tasks.isEmpty()) return Collections.emptyList();

            for (OutboxTask t : tasks) {
                db.mysql.update(
                    "UPDATE t_agent_memory_outbox SET state = 'PROCESSING', lease_owner = ?, " +
                        "lease_generation = lease_generation + 1, lease_until = TIMESTAMPADD(SECOND, ?, now(3)) " +
                        "WHERE id = ?",
                    leaseOwner, leaseSeconds, t.id()
                );
            }

            // Re-query updated tasks to capture incremented lease_generation
            List<Long> ids = tasks.stream().map(OutboxTask::id).toList();
            String inSql = String.join(",", Collections.nCopies(ids.size(), "?"));
            return db.mysql.query(
                "SELECT * FROM t_agent_memory_outbox WHERE id IN (" + inSql + ")",
                (rs, rowNum) -> mapOutbox(rs), ids.toArray()
            );
        });
    }

    public boolean completeTask(long taskId, long leaseGeneration) {
        return db.tx.execute(tx -> {
            int updated = db.mysql.update(
                "UPDATE t_agent_memory_outbox SET state = 'SUCCEEDED', finished_time = now(3) " +
                    "WHERE id = ? AND lease_generation = ?",
                taskId, leaseGeneration
            );
            return updated == 1;
        });
    }

    public boolean skipTask(long taskId, long leaseGeneration, String reason) {
        return db.tx.execute(tx -> {
            int updated = db.mysql.update(
                "UPDATE t_agent_memory_outbox SET state = 'SKIPPED', last_error_code = 'SKIPPED', " +
                    "last_error_message = ?, finished_time = now(3) WHERE id = ? AND lease_generation = ?",
                reason, taskId, leaseGeneration
            );
            return updated == 1;
        });
    }

    public boolean failTask(long taskId, long leaseGeneration, String errorCode, String errorMessage, boolean retryable, int currentAttempt) {
        return db.tx.execute(tx -> {
            if (retryable && currentAttempt < 8) {
                int[] intervals = {5, 30, 120, 600, 1800, 1800, 1800, 1800};
                int delaySec = intervals[Math.min(currentAttempt, intervals.length - 1)];
                int updated = db.mysql.update(
                    "UPDATE t_agent_memory_outbox SET state = 'RETRY_WAIT', attempt_count = attempt_count + 1, " +
                        "next_attempt_at = TIMESTAMPADD(SECOND, ?, now(3)), last_error_code = ?, last_error_message = ?, " +
                        "lease_owner = NULL, lease_until = NULL WHERE id = ? AND lease_generation = ?",
                    delaySec, errorCode, errorMessage, taskId, leaseGeneration
                );
                return updated == 1;
            } else {
                int updated = db.mysql.update(
                    "UPDATE t_agent_memory_outbox SET state = 'DEAD', attempt_count = attempt_count + 1, " +
                        "last_error_code = ?, last_error_message = ?, finished_time = now(3) " +
                        "WHERE id = ? AND lease_generation = ?",
                    errorCode, errorMessage, taskId, leaseGeneration
                );
                return updated == 1;
            }
        });
    }

    // ----------------------------------------------------------------------------------
    // Merge Candidates in MySQL Transaction
    // ----------------------------------------------------------------------------------

    public boolean mergeCandidatesInTransaction(
        long userId,
        String subjectKey,
        long expectedEpoch,
        String sourceEventId,
        String sourceMessageNo,
        String sourceSessionNo,
        String sourceRunNo,
        long sourceSequence,
        List<ValidatedCandidate> candidates,
        long outboxTaskId,
        long leaseGeneration
    ) {
        return db.tx.execute(tx -> {
            // 1. Lock setting
            MemorySetting setting = getSettingForUpdate(userId);
            if (setting.epoch() != expectedEpoch || !setting.writeEnabled()) {
                log.info("Skipping memory merge: user {} epoch changed or write disabled (current epoch={}, expected={})",
                    userId, setting.epoch(), expectedEpoch);
                skipTask(outboxTaskId, leaseGeneration, "EPOCH_MISMATCH_OR_WRITE_DISABLED");
                return false;
            }
            if (sourceSequence <= setting.captureAfterSequence()) {
                log.info("Skipping memory merge: sourceSequence {} <= captureAfterSequence {}",
                    sourceSequence, setting.captureAfterSequence());
                skipTask(outboxTaskId, leaseGeneration, "SOURCE_SEQUENCE_BELOW_THRESHOLD");
                return false;
            }

            // 2. Query Scope Barriers
            var barriers = db.mysql.query(
                "SELECT * FROM t_agent_memory_scope_barrier WHERE user_id = ?",
                (rs, rowNum) -> new MemoryScopeBarrier(
                    rs.getLong("id"), rs.getLong("user_id"), rs.getString("normalized_scope_key"),
                    read(rs.getString("scope_json")), rs.getLong("blocked_through_sequence")
                ), userId
            );

            // Sort candidates by preferenceKey to prevent deadlocks
            List<ValidatedCandidate> sorted = new ArrayList<>(candidates);
            sorted.sort(Comparator.comparing(ValidatedCandidate::preferenceKey));

            Instant now = Instant.now();
            boolean anyChanged = false;

            for (ValidatedCandidate c : sorted) {
                // Check barrier
                boolean blockedByBarrier = barriers.stream().anyMatch(b ->
                    c.preferenceKey().contains(b.normalizedScopeKey()) && sourceSequence <= b.blockedThroughSequence()
                );
                if (blockedByBarrier) {
                    log.info("Candidate {} blocked by scope barrier for user {}", c.preferenceKey(), userId);
                    continue;
                }

                // Lock head for this key
                var heads = db.mysql.query(
                    "SELECT * FROM t_agent_memory_head WHERE user_id = ? AND preference_key = ? FOR UPDATE",
                    (rs, rowNum) -> mapHead(rs), userId, c.preferenceKey()
                );

                if (heads.isEmpty()) {
                    // Check capacity (max 100 active memories)
                    Integer activeCount = db.mysql.queryForObject(
                        "SELECT COUNT(*) FROM t_agent_memory_head WHERE user_id = ? AND deleted = 0 AND epoch = ?",
                        Integer.class, userId, setting.epoch()
                    );
                    if (activeCount != null && activeCount >= 100) {
                        log.warn("User {} reached memory capacity (100). Rejecting new key {}", userId, c.preferenceKey());
                        continue;
                    }

                    String logicalId = "CL_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
                    String memoryNo = "CM_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
                    Instant expiresAt = now.plus(c.type() == MemoryType.PRICE_RANGE || c.type() == MemoryType.SPEC_PREFERENCE ? 90 : 180, ChronoUnit.DAYS);

                    // Insert record
                    db.mysql.update(
                        "INSERT INTO t_agent_memory_record(memory_no, logical_id, version, user_id, subject_key, epoch, " +
                            "memory_type, preference_key, scope_json, value_json, polarity, canonical_text, " +
                            "source_event_id, source_message_no, source_session_no, source_run_no, source_sequence, " +
                            "evidence_text, evidence_kind, confidence, confirmation_count, status, observed_at, last_confirmed_at, expires_at) " +
                            "VALUES (?, ?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'EXPLICIT_USER_STATEMENT', ?, 1, 'ACTIVE', ?, ?, ?)",
                        memoryNo, logicalId, userId, subjectKey, setting.epoch(), c.type().name(), c.preferenceKey(),
                        write(c.scope()), write(c.value()), c.polarity().name(), c.canonicalText(),
                        sourceEventId, sourceMessageNo, sourceSessionNo, sourceRunNo, sourceSequence,
                        c.quote(), c.confidence(), Timestamp.from(now), Timestamp.from(now), Timestamp.from(expiresAt)
                    );

                    // Insert head
                    db.mysql.update(
                        "INSERT INTO t_agent_memory_head(user_id, preference_key, logical_id, current_memory_no, current_version, epoch, " +
                            "last_source_sequence, blocked_through_sequence, deleted, row_version) " +
                            "VALUES (?, ?, ?, ?, 1, ?, ?, 0, 0, 1)",
                        userId, c.preferenceKey(), logicalId, memoryNo, setting.epoch(), sourceSequence
                    );

                    audit(userId, sourceEventId, memoryNo, logicalId, "CREATE", "SUCCESS",
                        object().put("preferenceKey", c.preferenceKey()).put("canonicalText", c.canonicalText()),
                        sourceRunNo, null, "SYSTEM");
                    anyChanged = true;
                } else {
                    MemoryHead head = heads.get(0);
                    // Check if candidate is late
                    if (sourceSequence <= head.lastSourceSequence() || sourceSequence <= head.blockedThroughSequence()) {
                        log.info("Candidate {} is late or blocked (seq={}, last={}, blocked={})",
                            c.preferenceKey(), sourceSequence, head.lastSourceSequence(), head.blockedThroughSequence());
                        continue;
                    }

                    int newVersion = head.currentVersion() + 1;
                    String newMemoryNo = "CM_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
                    Instant expiresAt = now.plus(c.type() == MemoryType.PRICE_RANGE || c.type() == MemoryType.SPEC_PREFERENCE ? 90 : 180, ChronoUnit.DAYS);

                    // Insert new record
                    db.mysql.update(
                        "INSERT INTO t_agent_memory_record(memory_no, logical_id, version, user_id, subject_key, epoch, " +
                            "memory_type, preference_key, scope_json, value_json, polarity, canonical_text, " +
                            "source_event_id, source_message_no, source_session_no, source_run_no, source_sequence, " +
                            "evidence_text, evidence_kind, confidence, confirmation_count, status, observed_at, last_confirmed_at, expires_at) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'EXPLICIT_USER_STATEMENT', ?, 1, 'ACTIVE', ?, ?, ?)",
                        newMemoryNo, head.logicalId(), newVersion, userId, subjectKey, setting.epoch(), c.type().name(), c.preferenceKey(),
                        write(c.scope()), write(c.value()), c.polarity().name(), c.canonicalText(),
                        sourceEventId, sourceMessageNo, sourceSessionNo, sourceRunNo, sourceSequence,
                        c.quote(), c.confidence(), Timestamp.from(now), Timestamp.from(now), Timestamp.from(expiresAt)
                    );

                    // Supersede old record
                    db.mysql.update(
                        "UPDATE t_agent_memory_record SET status = 'SUPERSEDED', superseded_by = ?, updated_time = now(3) " +
                            "WHERE memory_no = ?",
                        newMemoryNo, head.currentMemoryNo()
                    );

                    // Update head pointer
                    db.mysql.update(
                        "UPDATE t_agent_memory_head SET current_memory_no = ?, current_version = ?, epoch = ?, " +
                            "last_source_sequence = ?, deleted = 0, row_version = row_version + 1, updated_time = now(3) " +
                            "WHERE id = ?",
                        newMemoryNo, newVersion, setting.epoch(), sourceSequence, head.id()
                    );

                    audit(userId, sourceEventId, newMemoryNo, head.logicalId(), "UPDATE", "SUCCESS",
                        object().put("preferenceKey", c.preferenceKey()).put("version", newVersion),
                        sourceRunNo, null, "SYSTEM");
                    anyChanged = true;
                }
            }

            if (anyChanged) {
                db.mysql.update(
                    "UPDATE t_agent_memory_setting SET revision = revision + 1, updated_time = now(3) WHERE user_id = ?",
                    userId
                );
            }

            // Finally, CAS complete outbox task
            int updated = db.mysql.update(
                "UPDATE t_agent_memory_outbox SET state = 'SUCCEEDED', finished_time = now(3) " +
                    "WHERE id = ? AND lease_generation = ?",
                outboxTaskId, leaseGeneration
            );
            if (updated != 1) {
                throw error(409, "LEASE_GENERATION_LOST", "租约已失效，放弃提交");
            }
            return true;
        });
    }

    // ----------------------------------------------------------------------------------
    // User Control: Single Delete & Clear All
    // ----------------------------------------------------------------------------------

    public boolean deleteMemory(long userId, String memoryNo, String clientOpId) {
        return db.tx.execute(tx -> {
            MemorySetting setting = getSettingForUpdate(userId);
            long opSeq = allocateNextSourceSequence(userId);

            var records = db.mysql.query(
                "SELECT * FROM t_agent_memory_record WHERE memory_no = ? AND user_id = ? FOR UPDATE",
                (rs, rowNum) -> mapRecord(rs), memoryNo, userId
            );
            if (records.isEmpty()) {
                throw error(404, "MEMORY_NOT_FOUND", "指定的记忆不存在");
            }
            MemoryRecord record = records.get(0);

            // Mark record as DELETED
            db.mysql.update(
                "UPDATE t_agent_memory_record SET status = 'DELETED', deleted_at = now(3), updated_time = now(3) WHERE id = ?",
                record.id()
            );

            // Advance head barrier
            db.mysql.update(
                "UPDATE t_agent_memory_head SET deleted = 1, blocked_through_sequence = ?, updated_time = now(3) " +
                    "WHERE user_id = ? AND preference_key = ?",
                opSeq, userId, record.preferenceKey()
            );

            // Increment revision
            db.mysql.update(
                "UPDATE t_agent_memory_setting SET revision = revision + 1, updated_time = now(3) WHERE user_id = ?",
                userId
            );

            // Record operation
            recordOperation(userId, clientOpId, "DELETE_MEMORY", record.preferenceKey(), "FORGOTTEN", opSeq, setting.epoch(), setting.revision() + 1);
            audit(userId, null, memoryNo, record.logicalId(), "DELETE", "SUCCESS", object().put("preferenceKey", record.preferenceKey()), null, null, "USER");
            return true;
        });
    }

    public boolean clearAllMemories(long userId, String clientOpId) {
        return db.tx.execute(tx -> {
            MemorySetting setting = getSettingForUpdate(userId);
            long opSeq = allocateNextSourceSequence(userId);
            long newEpoch = setting.epoch() + 1;
            long newRevision = setting.revision() + 1;

            // Invalidate current records
            db.mysql.update(
                "UPDATE t_agent_memory_record SET status = 'DELETED', deleted_at = now(3), updated_time = now(3) " +
                    "WHERE user_id = ? AND status = 'ACTIVE'",
                userId
            );

            // Invalidate all heads
            db.mysql.update(
                "UPDATE t_agent_memory_head SET deleted = 1, blocked_through_sequence = ?, epoch = ?, updated_time = now(3) " +
                    "WHERE user_id = ?",
                opSeq, newEpoch, userId
            );

            // Advance setting epoch and captureAfterSequence
            db.mysql.update(
                "UPDATE t_agent_memory_setting SET epoch = ?, revision = ?, capture_after_sequence = ?, updated_time = now(3) " +
                    "WHERE user_id = ?",
                newEpoch, newRevision, opSeq, userId
            );

            recordOperation(userId, clientOpId, "CLEAR_ALL", "ALL", "FORGOTTEN", opSeq, newEpoch, newRevision);
            audit(userId, null, null, null, "CLEAR", "SUCCESS", object().put("epoch", newEpoch), null, null, "USER");
            return true;
        });
    }

    public boolean patchMemory(long userId, String memoryNo, JsonNode valueJson, JsonNode scopeJson, Integer expectedVersion, String clientOpId) {
        return db.tx.execute(tx -> {
            MemorySetting setting = getSettingForUpdate(userId);
            long opSeq = allocateNextSourceSequence(userId);

            var records = db.mysql.query(
                "SELECT * FROM t_agent_memory_record WHERE memory_no = ? AND user_id = ? FOR UPDATE",
                (rs, rowNum) -> mapRecord(rs), memoryNo, userId
            );
            if (records.isEmpty()) throw error(404, "MEMORY_NOT_FOUND", "指定的记忆不存在");
            MemoryRecord old = records.get(0);
            if (expectedVersion != null && old.version() != expectedVersion) {
                throw error(409, "MEMORY_VERSION_CONFLICT", "偏好已被修改，请刷新后重试");
            }

            int newVersion = old.version() + 1;
            String newMemoryNo = "CM_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            Instant now = Instant.now();

            // Insert new version
            db.mysql.update(
                "INSERT INTO t_agent_memory_record(memory_no, logical_id, version, user_id, subject_key, epoch, " +
                    "memory_type, preference_key, scope_json, value_json, polarity, canonical_text, " +
                    "source_event_id, source_message_no, source_session_no, source_run_no, source_sequence, " +
                    "evidence_text, evidence_kind, confidence, confirmation_count, status, observed_at, last_confirmed_at, expires_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'USER_EDIT', NULL, NULL, NULL, ?, " +
                    "'用户手动编辑', 'CONFIRMED_SELECTION', 1.0000, 1, 'ACTIVE', ?, ?, ?)",
                newMemoryNo, old.logicalId(), newVersion, userId, setting.subjectKey(), setting.epoch(),
                old.memoryType().name(), old.preferenceKey(), write(scopeJson != null ? scopeJson : old.scopeJson()),
                write(valueJson != null ? valueJson : old.valueJson()), old.polarity().name(), old.canonicalText(),
                opSeq, Timestamp.from(now), Timestamp.from(now), Timestamp.from(old.expiresAt())
            );

            // Supersede old record
            db.mysql.update("UPDATE t_agent_memory_record SET status = 'SUPERSEDED', superseded_by = ? WHERE id = ?",
                newMemoryNo, old.id());

            // Update head
            db.mysql.update(
                "UPDATE t_agent_memory_head SET current_memory_no = ?, current_version = ?, last_source_sequence = ?, " +
                    "row_version = row_version + 1, updated_time = now(3) WHERE user_id = ? AND preference_key = ?",
                newMemoryNo, newVersion, opSeq, userId, old.preferenceKey()
            );

            db.mysql.update("UPDATE t_agent_memory_setting SET revision = revision + 1 WHERE user_id = ?", userId);

            recordOperation(userId, clientOpId, "PATCH_MEMORY", old.preferenceKey(), "UPDATED", opSeq, setting.epoch(), setting.revision() + 1);
            audit(userId, null, newMemoryNo, old.logicalId(), "UPDATE", "SUCCESS", object().put("version", newVersion), null, null, "USER");
            return true;
        });
    }

    public ObjectNode getOperation(long userId, String operationNo) {
        var list = db.mysql.query(
            "SELECT * FROM t_agent_memory_operation WHERE user_id = ? AND operation_no = ?",
            (rs, rowNum) -> object()
                .put("operationNo", rs.getString("operation_no"))
                .put("operationType", rs.getString("operation_type"))
                .put("effectiveState", rs.getString("effective_state"))
                .put("purgeState", rs.getString("purge_state"))
                .put("revision", rs.getLong("revision")),
            userId, operationNo
        );
        if (list.isEmpty()) throw error(404, "OPERATION_NOT_FOUND", "操作不存在");
        return list.get(0);
    }

    private void recordOperation(long userId, String clientOpId, String opType, String targetScope, String effectiveState, long seq, long epoch, long revision) {
        String opNo = "MO_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String safeClientOpId = clientOpId != null && !clientOpId.isBlank() ? clientOpId : opNo;
        db.mysql.update(
            "INSERT INTO t_agent_memory_operation(operation_no, user_id, client_operation_id, request_hash, operation_type, " +
                "target_scope, effective_state, purge_state, source_sequence, epoch, revision, created_time, finished_time) " +
                "VALUES (?, ?, ?, 'NONE', ?, ?, ?, 'COMPLETED', ?, ?, ?, now(3), now(3)) " +
                "ON DUPLICATE KEY UPDATE effective_state = VALUES(effective_state), revision = VALUES(revision)",
            opNo, userId, safeClientOpId, opType, targetScope, effectiveState, seq, epoch, revision
        );
    }

    private void audit(long userId, String eventId, String memoryNo, String logicalId, String action, String result,
                       JsonNode detail, String runNo, String traceId, String operatorType) {
        String auditNo = "MA_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        db.mysql.update(
            "INSERT INTO t_agent_memory_audit(audit_no, event_id, memory_no, logical_id, user_id, action, result, detail_json, run_no, trace_id, operator_type) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            auditNo, eventId, memoryNo, logicalId, userId, action, result, write(detail), runNo, traceId, operatorType
        );
    }

    // ----------------------------------------------------------------------------------
    // Row Mappers
    // ----------------------------------------------------------------------------------

    private static MemorySetting mapSetting(ResultSet rs) throws SQLException {
        Timestamp enabled = rs.getTimestamp("enabled_at");
        return new MemorySetting(
            rs.getLong("user_id"),
            rs.getString("subject_key"),
            rs.getInt("read_enabled") == 1,
            rs.getInt("write_enabled") == 1,
            rs.getLong("epoch"),
            rs.getLong("revision"),
            rs.getString("policy_version"),
            enabled != null ? enabled.toInstant() : null,
            rs.getLong("capture_after_sequence"),
            rs.getLong("next_source_sequence")
        );
    }

    private static MemoryHead mapHead(ResultSet rs) throws SQLException {
        return new MemoryHead(
            rs.getLong("id"),
            rs.getLong("user_id"),
            rs.getString("preference_key"),
            rs.getString("logical_id"),
            rs.getString("current_memory_no"),
            rs.getInt("current_version"),
            rs.getLong("epoch"),
            rs.getLong("last_source_sequence"),
            rs.getLong("blocked_through_sequence"),
            rs.getInt("deleted") == 1,
            rs.getLong("row_version")
        );
    }

    private static MemoryRecord mapRecord(ResultSet rs) throws SQLException {
        Timestamp obs = rs.getTimestamp("observed_at");
        Timestamp conf = rs.getTimestamp("last_confirmed_at");
        Timestamp exp = rs.getTimestamp("expires_at");
        Timestamp del = rs.getTimestamp("deleted_at");

        return new MemoryRecord(
            rs.getLong("id"),
            rs.getString("memory_no"),
            rs.getString("logical_id"),
            rs.getInt("version"),
            rs.getLong("user_id"),
            rs.getString("subject_key"),
            rs.getLong("epoch"),
            MemoryType.valueOf(rs.getString("memory_type")),
            rs.getString("preference_key"),
            read(rs.getString("scope_json")),
            read(rs.getString("value_json")),
            Polarity.valueOf(rs.getString("polarity")),
            rs.getString("canonical_text"),
            rs.getString("source_event_id"),
            rs.getString("source_message_no"),
            rs.getString("source_session_no"),
            rs.getString("source_run_no"),
            rs.getLong("source_sequence"),
            rs.getString("evidence_hash"),
            rs.getString("evidence_text"),
            EvidenceKind.valueOf(rs.getString("evidence_kind")),
            rs.getDouble("confidence"),
            rs.getInt("confirmation_count"),
            rs.getString("schema_version"),
            rs.getString("extractor_version"),
            MemoryStatus.valueOf(rs.getString("status")),
            obs != null ? obs.toInstant() : null,
            conf != null ? conf.toInstant() : null,
            exp != null ? exp.toInstant() : null,
            rs.getString("superseded_by"),
            del != null ? del.toInstant() : null
        );
    }

    private static OutboxTask mapOutbox(ResultSet rs) throws SQLException {
        Timestamp next = rs.getTimestamp("next_attempt_at");
        Timestamp until = rs.getTimestamp("lease_until");
        return new OutboxTask(
            rs.getLong("id"),
            rs.getString("event_id"),
            OutboxType.valueOf(rs.getString("event_type")),
            rs.getString("dedupe_key"),
            rs.getLong("user_id"),
            rs.getString("subject_key"),
            rs.getLong("epoch"),
            rs.getLong("source_sequence"),
            read(rs.getString("payload_json")),
            OutboxState.valueOf(rs.getString("state")),
            rs.getInt("attempt_count"),
            next != null ? next.toInstant() : null,
            rs.getString("lease_owner"),
            rs.getLong("lease_generation"),
            until != null ? until.toInstant() : null,
            rs.getString("last_error_code"),
            rs.getString("last_error_message"),
            rs.getString("trace_id")
        );
    }
}
