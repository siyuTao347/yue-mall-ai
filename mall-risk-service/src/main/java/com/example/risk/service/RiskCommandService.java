package com.example.risk.service;

import api.risk.RiskCommandDTO;
import api.risk.RiskCommandResultDTO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.risk.entity.RiskCase;
import com.example.risk.entity.RiskCaseNote;
import com.example.risk.exception.RiskApiException;
import com.example.risk.mapper.RiskCaseMapper;
import com.example.risk.mapper.RiskCaseNoteMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 案件操作与风控命令投递。所有状态变更使用条件更新 + operationVersion 做并发保护。
 */
@Service
@Slf4j
public class RiskCommandService {
    public static final String COMMAND_TOPIC = "risk-command-topic";
    private static final String DEAD_LETTER_STATUS = "DEAD_LETTER";
    private static final String RAW_DEAD_LETTER_STATUS = "COMMAND_FAILED";
    private static final List<String> ORDER_SCENES =
            List.of("ORDER", "PAYMENT", "DELIVERY", "CONFIRM", "DISPUTE");
    private static final Map<String, Set<String>> COMMAND_PARAMS = Map.of(
            "APPROVE", Set.of("amount", "remark", "scope"),
            "REJECT", Set.of("reason", "remark"),
            "FREEZE", Set.of("targetType", "scope", "freezeDays", "remark"),
            "LIMIT", Set.of("scope", "limitAmount", "remark"),
            "DELAY_SETTLE", Set.of("delayMinutes", "delayHours", "orderNo", "remark"));

    private final RiskCaseMapper caseMapper;
    private final RiskCaseNoteMapper noteMapper;
    private final RocketMQTemplate rocketMQTemplate;
    private final ObjectMapper objectMapper;
    private final RiskDictionaryService dictionary;
    private final MeterRegistry meterRegistry;

    public RiskCommandService(RiskCaseMapper caseMapper, RiskCaseNoteMapper noteMapper,
                              RocketMQTemplate rocketMQTemplate, ObjectMapper objectMapper,
                              RiskDictionaryService dictionary, MeterRegistry meterRegistry) {
        this.caseMapper = caseMapper;
        this.noteMapper = noteMapper;
        this.rocketMQTemplate = rocketMQTemplate;
        this.objectMapper = objectMapper;
        this.dictionary = dictionary;
        this.meterRegistry = meterRegistry;
    }

    @Transactional(rollbackFor = Exception.class)
    public RiskCase assign(String caseNo, Long operatorId, String operatorName,
                           String expectedStatus, Long expectedVersion) {
        requireOperator(operatorId, operatorName);
        requireExpected("OPEN", expectedStatus, "认领案件 expectedStatus 必须为 OPEN");
        RiskCase riskCase = requireCase(caseNo);
        requireStatus(riskCase, expectedStatus);
        LocalDateTime now = LocalDateTime.now();
        if (caseMapper.assignVersioned(riskCase.getId(), operatorId, expectedStatus,
                expectedVersion, now) <= 0) {
            throw RiskApiException.stateChanged();
        }
        insertNote(caseNo, "ASSIGN", expectedStatus, "PROCESSING", "认领案件", operatorId, operatorName,
                evidence(Map.of("caseNo", caseNo, "operationVersion", expectedVersion)));
        return requireCase(caseNo);
    }

    @Transactional(rollbackFor = Exception.class)
    public RiskCase resolve(String caseNo, Long operatorId, String operatorName,
                            String expectedStatus, Long expectedVersion, String command,
                            String reason, Map<String, Object> actionParams) {
        requireOperator(operatorId, operatorName);
        requireExpected("PROCESSING", expectedStatus, "处置案件 expectedStatus 必须为 PROCESSING");
        RiskCase riskCase = requireCase(caseNo);
        requireStatus(riskCase, expectedStatus);
        requireSupportedCommand(riskCase.getScene(), command);
        Map<String, Object> params = validateActionParams(command, actionParams);
        String commandNo = nextCommandNo();
        RiskCommandDTO message = buildCommand(riskCase, commandNo, command, reason, operatorId, params);
        String commandJson = writeJson(message);
        if (caseMapper.resolveVersioned(riskCase.getId(), operatorId, command, reason, commandNo,
                commandJson, expectedStatus, expectedVersion, LocalDateTime.now()) <= 0) {
            throw RiskApiException.stateChanged();
        }
        sendAfterCommit(riskCase.getId(), message);
        insertNote(caseNo, "RESOLVE", expectedStatus, "RESOLVED", reason, operatorId, operatorName,
                evidence(Map.of("commandNo", commandNo, "command", command,
                        "operationVersion", expectedVersion)));
        return requireCase(caseNo);
    }

    @Transactional(rollbackFor = Exception.class)
    public RiskCase close(String caseNo, Long operatorId, String operatorName,
                          String expectedStatus, String expectedCommandStatus,
                          Long expectedVersion, String reason) {
        requireOperator(operatorId, operatorName);
        requireExpected("RESOLVED", expectedStatus, "关闭案件 expectedStatus 必须为 RESOLVED");
        requireExpected("SUCCESS", expectedCommandStatus, "关闭案件命令必须已执行成功");
        RiskCase riskCase = requireCase(caseNo);
        requireStatus(riskCase, expectedStatus);
        if (!expectedCommandStatus.equals(riskCase.getCommandStatus())) {
            throw RiskApiException.stateChanged();
        }
        if (caseMapper.closeVersioned(riskCase.getId(), expectedStatus, expectedCommandStatus,
                expectedVersion, LocalDateTime.now()) <= 0) {
            throw RiskApiException.stateChanged();
        }
        insertNote(caseNo, "CLOSE", expectedStatus, "CLOSED", reason, operatorId, operatorName,
                evidence(Map.of("commandStatus", expectedCommandStatus, "operationVersion", expectedVersion)));
        return requireCase(caseNo);
    }

    @Transactional(rollbackFor = Exception.class)
    public RiskCase reopen(String caseNo, Long operatorId, String operatorName,
                           String expectedStatus, String expectedCommandStatus,
                           Long expectedVersion, String reason) {
        requireOperator(operatorId, operatorName);
        requireExpected("CLOSED", expectedStatus, "重开案件 expectedStatus 必须为 CLOSED");
        RiskCase riskCase = requireCase(caseNo);
        requireStatus(riskCase, expectedStatus);
        String currentCommandStatus = riskCase.getCommandStatus() == null ? "NONE" : riskCase.getCommandStatus();
        if (!currentCommandStatus.equals(normalizeCommandStatus(expectedCommandStatus))) {
            throw RiskApiException.stateChanged();
        }
        if (caseMapper.reopenVersioned(riskCase.getId(), expectedStatus, currentCommandStatus,
                expectedVersion, LocalDateTime.now()) <= 0) {
            throw RiskApiException.stateChanged();
        }
        insertNote(caseNo, "REOPEN", expectedStatus, "OPEN", reason, operatorId, operatorName,
                evidence(Map.of("reopenCount", riskCase.getReopenCount() == null ? 1
                        : riskCase.getReopenCount() + 1, "operationVersion", expectedVersion)));
        return requireCase(caseNo);
    }

    @Transactional(rollbackFor = Exception.class)
    public RiskCase resend(String caseNo, String commandNo, Long operatorId, String operatorName,
                           String expectedStatus, String expectedCommandStatus,
                           Long expectedVersion, String reason) {
        requireOperator(operatorId, operatorName);
        requireExpected("RESOLVED", expectedStatus, "重发命令 expectedStatus 必须为 RESOLVED");
        String normalizedStatus = normalizeCommandStatus(expectedCommandStatus);
        if (!"FAILED".equals(normalizedStatus) && !RAW_DEAD_LETTER_STATUS.equals(normalizedStatus)) {
            throw RiskApiException.badRequest(RiskApiException.INVALID_OPERATION,
                    "仅失败或死信命令可以重发");
        }
        RiskCase riskCase = requireCase(caseNo);
        requireStatus(riskCase, expectedStatus);
        if (!commandNo.equals(riskCase.getLastCommandNo()) || riskCase.getLastCommandJson() == null) {
            throw RiskApiException.notFound(RiskApiException.COMMAND_NOT_FOUND, "风控命令不存在");
        }
        if (!normalizedStatus.equals(riskCase.getCommandStatus())) {
            throw RiskApiException.stateChanged();
        }
        RiskCommandDTO message = readJson(riskCase.getLastCommandJson());
        if (!commandNo.equals(message.getCommandNo())) {
            throw RiskApiException.notFound(RiskApiException.COMMAND_NOT_FOUND, "风控命令编号不匹配");
        }
        if (caseMapper.markCommandResentVersioned(riskCase.getId(), commandNo, normalizedStatus,
                expectedVersion, LocalDateTime.now()) <= 0) {
            throw RiskApiException.stateChanged();
        }
        sendAfterCommit(riskCase.getId(), message);
        insertNote(caseNo, "COMMAND_RESEND", expectedStatus, expectedStatus, reason,
                operatorId, operatorName, evidence(Map.of("commandNo", commandNo,
                        "expectedCommandStatus", normalizedStatus, "operationVersion", expectedVersion)));
        log.warn("风控命令人工重发, caseNo={}, commandNo={}, operatorId={}, expectedCommandStatus={}",
                caseNo, commandNo, operatorId, normalizedStatus);
        meterRegistry.counter("risk_command_manual_operation_total", "operation", "RESEND").increment();
        return requireCase(caseNo);
    }

    @Transactional(rollbackFor = Exception.class)
    public RiskCase manualComplete(String caseNo, String commandNo, Long operatorId, String operatorName,
                                   String expectedCommandStatus, String result, String reason,
                                   String evidenceText) {
        requireOperator(operatorId, operatorName);
        if (!dictionary.manualCompleteEnabled()) {
            throw RiskApiException.forbidden(RiskApiException.MANUAL_COMPLETE_DISABLED,
                    "人工处理入口当前已关闭");
        }
        String normalizedStatus = normalizeCommandStatus(expectedCommandStatus);
        if (!RAW_DEAD_LETTER_STATUS.equals(normalizedStatus) && !"FAILED".equals(normalizedStatus)) {
            throw RiskApiException.badRequest(RiskApiException.INVALID_OPERATION,
                    "仅失败或死信命令可以人工确认");
        }
        RiskCase riskCase = requireCase(caseNo);
        if (!commandNo.equals(riskCase.getLastCommandNo())) {
            throw RiskApiException.notFound(RiskApiException.COMMAND_NOT_FOUND, "风控命令不存在");
        }
        if (!normalizedStatus.equals(riskCase.getCommandStatus())) {
            throw RiskApiException.stateChanged();
        }
        String newStatus = "SUCCESS".equals(result) ? "SUCCESS" : RAW_DEAD_LETTER_STATUS;
        String lastError = "SUCCESS".equals(result) ? null : safeError(reason);
        LocalDateTime now = LocalDateTime.now();
        if (caseMapper.manualComplete(riskCase.getId(), commandNo, normalizedStatus, newStatus,
                lastError, now) <= 0) {
            throw RiskApiException.stateChanged();
        }
        Map<String, Object> audit = new HashMap<>();
        audit.put("commandNo", commandNo);
        audit.put("expectedCommandStatus", normalizedStatus);
        audit.put("result", result);
        audit.put("message", truncate(reason, 200));
        if (evidenceText != null && !evidenceText.isBlank()) {
            audit.put("supportingEvidence", truncate(evidenceText, 200));
        }
        insertNote(caseNo, "COMMAND_MANUAL_COMPLETE", riskCase.getStatus(), riskCase.getStatus(),
                reason, operatorId, operatorName, evidence(audit));
        log.warn("风控命令人工处理完成, caseNo={}, commandNo={}, operatorId={}, result={}, newStatus={}",
                caseNo, commandNo, operatorId, result, newStatus);
        meterRegistry.counter("risk_command_manual_operation_total", "operation", "MANUAL_COMPLETE").increment();
        return requireCase(caseNo);
    }

    @Transactional(rollbackFor = Exception.class)
    public void confirmCommand(RiskCommandResultDTO result) {
        requireResult(result);
        RiskCase riskCase = requireCaseByCommand(result.getCaseNo(), result.getCommandNo());
        LocalDateTime now = LocalDateTime.now();
        if (Boolean.TRUE.equals(result.getSuccess())) {
            if (caseMapper.markCommandSuccess(riskCase.getId(), result.getCommandNo(), now) > 0) {
                boolean updated = Boolean.TRUE.equals(result.getUpdated());
                insertSystemNote(riskCase.getCaseNo(), "COMMAND_SUCCESS", "RESOLVED", "RESOLVED",
                        updated ? "风控命令执行成功，业务状态已更新" : "风控命令执行完成，业务状态未变化",
                        evidence(result, updated));
            }
            return;
        }
        if (caseMapper.markCommandExecutionFailed(riskCase.getId(), result.getCommandNo(),
                safeError(result.getMessage()), now) > 0) {
            insertSystemNote(riskCase.getCaseNo(), "COMMAND_FAILED", "RESOLVED", "RESOLVED",
                    "风控命令执行失败，等待人工处理", evidence(result, false));
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void markDeadLetter(String commandNo) {
        if (commandNo == null || commandNo.isBlank()) {
            throw RiskApiException.badRequest(RiskApiException.COMMAND_NOT_FOUND, "风控命令编号不能为空");
        }
        RiskCase riskCase = requireCaseByCommand(null, commandNo);
        if (caseMapper.markCommandDeadLetter(riskCase.getId(), commandNo,
                "命令重试耗尽进入死信队列", LocalDateTime.now()) > 0) {
            Map<String, Object> evidence = new HashMap<>();
            evidence.put("commandNo", commandNo);
            evidence.put("scene", riskCase.getScene());
            evidence.put("message", "风控命令重试耗尽，进入死信队列");
            insertSystemNote(riskCase.getCaseNo(), "COMMAND_DEAD_LETTER", "RESOLVED", "RESOLVED",
                    "风控命令重试耗尽，进入死信队列", evidence);
        }
    }

    private Map<String, Object> validateActionParams(String command, Map<String, Object> actionParams) {
        Map<String, Object> params = actionParams == null ? Map.of() : actionParams;
        Set<String> allowed = COMMAND_PARAMS.getOrDefault(command, Set.of());
        Map<String, Object> sanitized = new HashMap<>();
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            if (!allowed.contains(entry.getKey())) {
                throw RiskApiException.badRequest(RiskApiException.INVALID_OPERATION,
                        "actionParams 不支持字段: " + entry.getKey());
            }
            Object value = entry.getValue();
            if (value != null && !(value instanceof String) && !(value instanceof Number)
                    && !(value instanceof Boolean)) {
                throw RiskApiException.badRequest(RiskApiException.INVALID_OPERATION,
                        "actionParams 字段只支持标量: " + entry.getKey());
            }
            sanitized.put(entry.getKey(), value instanceof String text ? truncate(text, 255) : value);
        }
        if ("DELAY_SETTLE".equals(command)
                && !(sanitized.containsKey("delayMinutes") || sanitized.containsKey("delayHours")
                || sanitized.containsKey("orderNo"))) {
            throw RiskApiException.badRequest(RiskApiException.INVALID_OPERATION,
                    "延迟结算需要 delayMinutes、delayHours 或 orderNo");
        }
        if ("FREEZE".equals(command)
                && !(sanitized.containsKey("targetType") || sanitized.containsKey("scope"))) {
            throw RiskApiException.badRequest(RiskApiException.INVALID_OPERATION,
                    "冻结需要 targetType 或 scope");
        }
        return sanitized;
    }

    private RiskCommandDTO buildCommand(RiskCase riskCase, String commandNo, String command, String reason,
                                        Long operatorId, Map<String, Object> actionParams) {
        Map<String, Object> params = actionParams == null ? Map.of() : actionParams;
        RiskCommandDTO.RiskCommandDTOBuilder builder = RiskCommandDTO.builder()
                .commandNo(commandNo)
                .decisionNo(riskCase.getDecisionNo())
                .caseNo(riskCase.getCaseNo())
                .scene(riskCase.getScene())
                .bizNo(riskCase.getBizNo())
                .command(command)
                .reason(reason)
                .operatorId(operatorId)
                .actionParams(params);
        if ("LISTING".equals(riskCase.getScene())) {
            builder.itemId(parseLong(riskCase.getBizNo()));
        } else if (ORDER_SCENES.contains(riskCase.getScene())) {
            builder.orderNo(riskCase.getBizNo());
        } else if ("MERCHANT".equals(riskCase.getScene())) {
            builder.merchantId(parseLong(riskCase.getBizNo()));
        } else if ("WITHDRAW".equals(riskCase.getScene())) {
            builder.withdrawNo(riskCase.getBizNo());
        }
        return builder.build();
    }

    private void send(RiskCommandDTO message) {
        if (rocketMQTemplate.syncSend(COMMAND_TOPIC, message).getSendStatus() != SendStatus.SEND_OK) {
            throw new IllegalStateException("风控命令发送失败");
        }
    }

    private void sendAfterCommit(Long caseId, RiskCommandDTO message) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    sendAndMark(caseId, message);
                }
            });
            return;
        }
        sendAndMark(caseId, message);
    }

    private void sendAndMark(Long caseId, RiskCommandDTO message) {
        try {
            send(message);
            caseMapper.markCommandSent(caseId, message.getCommandNo(), LocalDateTime.now());
        } catch (RuntimeException e) {
            caseMapper.markCommandFailed(caseId, message.getCommandNo(), safeError(e.getMessage()),
                    LocalDateTime.now());
            throw e;
        }
    }

    private void requireSupportedCommand(String scene, String command) {
        if (command == null || !dictionary.commandsForScene(scene).contains(command)) {
            throw RiskApiException.badRequest(RiskApiException.INVALID_OPERATION, "案件场景不支持该处理命令");
        }
    }

    private void requireExpected(String required, String actual, String message) {
        if (!required.equals(actual)) {
            throw RiskApiException.badRequest(RiskApiException.INVALID_OPERATION, message);
        }
    }

    private void requireStatus(RiskCase riskCase, String expectedStatus) {
        if (!expectedStatus.equals(riskCase.getStatus())) {
            throw RiskApiException.stateChanged();
        }
    }

    private RiskCase requireCase(String caseNo) {
        RiskCase riskCase = caseMapper.selectOne(new LambdaQueryWrapper<RiskCase>()
                .eq(RiskCase::getCaseNo, caseNo));
        if (riskCase == null) {
            throw RiskApiException.notFound(RiskApiException.CASE_NOT_FOUND, "风控案件不存在");
        }
        return riskCase;
    }

    private RiskCase requireCaseByCommand(String caseNo, String commandNo) {
        LambdaQueryWrapper<RiskCase> wrapper = new LambdaQueryWrapper<>();
        if (caseNo != null && !caseNo.isBlank()) {
            wrapper.eq(RiskCase::getCaseNo, caseNo);
        } else {
            wrapper.eq(RiskCase::getLastCommandNo, commandNo);
        }
        RiskCase riskCase = caseMapper.selectOne(wrapper);
        if (riskCase == null || !commandNo.equals(riskCase.getLastCommandNo())) {
            throw RiskApiException.notFound(RiskApiException.COMMAND_NOT_FOUND, "风控命令不存在");
        }
        return riskCase;
    }

    private void requireResult(RiskCommandResultDTO result) {
        if (result == null || result.getCommandNo() == null || result.getCommandNo().isBlank()
                || result.getSuccess() == null) {
            throw RiskApiException.badRequest(RiskApiException.INVALID_OPERATION, "风控命令执行结果不合法");
        }
    }

    private Map<String, Object> evidence(RiskCommandResultDTO result, boolean updated) {
        Map<String, Object> evidence = new HashMap<>();
        evidence.put("commandNo", result.getCommandNo());
        evidence.put("scene", result.getScene());
        evidence.put("updated", updated);
        if (result.getMessage() != null && !result.getMessage().isBlank()) {
            evidence.put("message", truncate(result.getMessage(), 200));
        }
        return evidence;
    }

    private Map<String, Object> evidence(Map<String, Object> values) {
        return values == null ? Map.of() : values;
    }

    private void insertSystemNote(String caseNo, String type, String before, String after,
                                  String content, Map<String, Object> evidence) {
        insertNote(caseNo, type, before, after, content, 0L, "RISK_COMMAND_SYSTEM", evidence);
    }

    private void requireOperator(Long operatorId, String operatorName) {
        if (operatorId == null || operatorName == null || operatorName.isBlank()) {
            throw RiskApiException.badRequest(RiskApiException.INVALID_OPERATION, "操作人不能为空");
        }
    }

    private void insertNote(String caseNo, String type, String before, String after, String content,
                            Long operatorId, String operatorName, Map<String, Object> evidence) {
        RiskCaseNote note = new RiskCaseNote();
        note.setCaseNo(caseNo);
        note.setNoteType(type);
        note.setOperatorId(operatorId);
        note.setOperatorName(operatorName);
        note.setContent(content);
        note.setBeforeStatus(before);
        note.setAfterStatus(after);
        note.setEvidenceJson(writeJson(evidence));
        note.setCreatedTime(LocalDateTime.now());
        noteMapper.insert(note);
    }

    private Long parseLong(String value) {
        try {
            return value == null ? null : Long.valueOf(value);
        } catch (NumberFormatException e) {
            throw RiskApiException.badRequest(RiskApiException.INVALID_OPERATION, "案件业务编号不合法");
        }
    }

    private String nextCommandNo() {
        return "CMD-" + System.currentTimeMillis() + "-"
                + UUID.randomUUID().toString().replace("-", "");
    }

    private String normalizeCommandStatus(String commandStatus) {
        return DEAD_LETTER_STATUS.equals(commandStatus) ? RAW_DEAD_LETTER_STATUS : commandStatus;
    }

    private String safeError(String message) {
        if (message == null || message.isBlank()) {
            return null;
        }
        return truncate(message, 512);
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("风控命令序列化失败", e);
        }
    }

    private RiskCommandDTO readJson(String json) {
        try {
            return objectMapper.readValue(json, RiskCommandDTO.class);
        } catch (Exception e) {
            throw new IllegalStateException("风控命令反序列化失败", e);
        }
    }
}
