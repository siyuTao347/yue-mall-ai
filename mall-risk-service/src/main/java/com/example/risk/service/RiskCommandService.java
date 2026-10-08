package com.example.risk.service;

import api.common.PageResult;
import api.risk.RiskCommandDTO;
import api.risk.RiskCommandResultDTO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.risk.dto.RiskCaseListQuery;
import com.example.risk.dto.RiskCaseSummaryDTO;
import com.example.risk.entity.RiskCase;
import com.example.risk.entity.RiskCaseNote;
import com.example.risk.mapper.RiskCaseMapper;
import com.example.risk.mapper.RiskCaseNoteMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class RiskCommandService {
    public static final String COMMAND_TOPIC = "risk-command-topic";
    private static final List<String> ORDER_SCENES =
            List.of("ORDER", "PAYMENT", "DELIVERY", "CONFIRM", "DISPUTE");

    private final RiskCaseMapper caseMapper;
    private final RiskCaseNoteMapper noteMapper;
    private final RocketMQTemplate rocketMQTemplate;
    private final ObjectMapper objectMapper;

    public RiskCommandService(RiskCaseMapper caseMapper, RiskCaseNoteMapper noteMapper,
                              RocketMQTemplate rocketMQTemplate, ObjectMapper objectMapper) {
        this.caseMapper = caseMapper;
        this.noteMapper = noteMapper;
        this.rocketMQTemplate = rocketMQTemplate;
        this.objectMapper = objectMapper;
    }

    public PageResult<RiskCaseSummaryDTO> listCases(RiskCaseListQuery query, int page, int pageSize) {
        LambdaQueryWrapper<RiskCase> wrapper = new LambdaQueryWrapper<RiskCase>()
                .eq(query != null && query.status() != null, RiskCase::getStatus,
                        query == null ? null : query.status())
                .eq(query != null && query.scene() != null, RiskCase::getScene,
                        query == null ? null : query.scene())
                .eq(query != null && query.riskLevel() != null, RiskCase::getRiskLevel,
                        query == null ? null : query.riskLevel())
                .eq(query != null && query.commandStatus() != null, RiskCase::getCommandStatus,
                        query == null ? null : query.commandStatus())
                .eq(query != null && query.subjectType() != null, RiskCase::getSubjectType,
                        query == null ? null : query.subjectType())
                .eq(query != null && query.subjectId() != null, RiskCase::getSubjectId,
                        query == null ? null : query.subjectId())
                .eq(query != null && query.bizNo() != null, RiskCase::getBizNo,
                        query == null ? null : query.bizNo())
                .ge(query != null && query.timeRange() != null && query.timeRange().fromTime() != null,
                        RiskCase::getCreatedTime, query == null || query.timeRange() == null
                                ? null : query.timeRange().fromTime())
                .le(query != null && query.timeRange() != null && query.timeRange().toTime() != null,
                        RiskCase::getCreatedTime, query == null || query.timeRange() == null
                                ? null : query.timeRange().toTime())
                .orderByDesc(RiskCase::getUpdatedTime)
                .orderByDesc(RiskCase::getId);
        Page<RiskCase> result = caseMapper.selectPage(new Page<>(page, pageSize), wrapper);
        List<RiskCaseSummaryDTO> records = result.getRecords().stream().map(this::caseSummary).toList();
        return PageResult.of(records, result.getTotal(), page, pageSize);
    }

    private RiskCaseSummaryDTO caseSummary(RiskCase riskCase) {
        return new RiskCaseSummaryDTO(riskCase.getId(), riskCase.getCaseNo(), riskCase.getDecisionNo(),
                riskCase.getScene(), riskCase.getBizType(), riskCase.getBizNo(), riskCase.getSubjectType(),
                riskCase.getSubjectId(), riskCase.getRiskLevel(), riskCase.getRiskScore(), riskCase.getStatus(),
                riskCase.getAssignedTo(), riskCase.getResolvedAction(), riskCase.getResolveReason(),
                riskCase.getResolvedTime(), riskCase.getLastCommandNo(), riskCase.getCommandStatus(),
                riskCase.getCommandRetryCount(), riskCase.getReopenCount(), riskCase.getCreatedTime(),
                riskCase.getUpdatedTime());
    }

    public CaseDetail detail(String caseNo) {
        RiskCase riskCase = requireCase(caseNo);
        return new CaseDetail(riskCase, noteMapper.selectList(new LambdaQueryWrapper<RiskCaseNote>()
                .eq(RiskCaseNote::getCaseNo, caseNo)
                .orderByAsc(RiskCaseNote::getId)));
    }

    @Transactional(rollbackFor = Exception.class)
    public RiskCase assign(String caseNo, Long operatorId, String operatorName) {
        requireOperator(operatorId, operatorName);
        RiskCase riskCase = requireCase(caseNo);
        if (caseMapper.assign(riskCase.getId(), operatorId, LocalDateTime.now()) <= 0) {
            throw new IllegalStateException("只有未认领案件可以认领");
        }
        insertNote(caseNo, "ASSIGN", "OPEN", "PROCESSING", "认领案件", operatorId, operatorName,
                Map.of("caseNo", caseNo));
        return requireCase(caseNo);
    }

    @Transactional(rollbackFor = Exception.class)
    public RiskCase resolve(String caseNo, Long operatorId, String operatorName, String command,
                            String reason, Map<String, Object> actionParams) {
        requireOperator(operatorId, operatorName);
        RiskCase riskCase = requireCase(caseNo);
        requireSupportedCommand(riskCase.getScene(), command);
        String commandNo = nextCommandNo();
        RiskCommandDTO message = buildCommand(riskCase, commandNo, command, reason, operatorId, actionParams);
        String commandJson = writeJson(message);
        if (caseMapper.resolve(riskCase.getId(), operatorId, command, reason, commandNo,
                commandJson, LocalDateTime.now()) <= 0) {
            throw new IllegalStateException("案件未认领或已处理");
        }
        sendAfterCommit(riskCase.getId(), message);
        insertNote(caseNo, "RESOLVE", "PROCESSING", "RESOLVED", reason, operatorId, operatorName,
                Map.of("commandNo", commandNo, "command", command));
        return requireCase(caseNo);
    }

    @Transactional(rollbackFor = Exception.class)
    public RiskCase close(String caseNo, Long operatorId, String operatorName, String reason) {
        requireOperator(operatorId, operatorName);
        RiskCase riskCase = requireCase(caseNo);
        if (caseMapper.close(riskCase.getId(), LocalDateTime.now()) <= 0) {
            throw new IllegalStateException("只有已处理案件可以关闭");
        }
        insertNote(caseNo, "CLOSE", "RESOLVED", "CLOSED", reason, operatorId, operatorName,
                Map.of("commandStatus", "SUCCESS"));
        return requireCase(caseNo);
    }

    @Transactional(rollbackFor = Exception.class)
    public RiskCase reopen(String caseNo, Long operatorId, String operatorName, String reason) {
        requireOperator(operatorId, operatorName);
        RiskCase riskCase = requireCase(caseNo);
        if (caseMapper.reopenManually(riskCase.getId(), LocalDateTime.now()) <= 0) {
            throw new IllegalStateException("只有已关闭案件可以重开");
        }
        insertNote(caseNo, "REOPEN", "CLOSED", "OPEN", reason, operatorId, operatorName,
                Map.of("reopenCount", riskCase.getReopenCount() == null ? 1 : riskCase.getReopenCount() + 1));
        return requireCase(caseNo);
    }

    @Transactional(rollbackFor = Exception.class)
    public RiskCase resend(String caseNo, String commandNo, Long operatorId, String operatorName) {
        requireOperator(operatorId, operatorName);
        RiskCase riskCase = requireCase(caseNo);
        if (!commandNo.equals(riskCase.getLastCommandNo()) || riskCase.getLastCommandJson() == null) {
            throw new IllegalArgumentException("命令不存在");
        }
        RiskCommandDTO message = readJson(riskCase.getLastCommandJson());
        if (!commandNo.equals(message.getCommandNo())) {
            throw new IllegalArgumentException("命令编号不匹配");
        }
        if (caseMapper.markCommandResent(riskCase.getId(), commandNo, LocalDateTime.now()) <= 0) {
            throw new IllegalStateException("只有已处理案件可以补偿重发");
        }
        sendAfterCommit(riskCase.getId(), message);
        insertNote(caseNo, "COMMAND_RESEND", riskCase.getStatus(), riskCase.getStatus(),
                "补偿重发风控命令", operatorId, operatorName, Map.of("commandNo", commandNo));
        return requireCase(caseNo);
    }

    @Transactional(rollbackFor = Exception.class)
    public void confirmCommand(RiskCommandResultDTO result) {
        requireResult(result);
        RiskCase riskCase = requireCaseByCommand(result.getCaseNo(), result.getCommandNo());
        if (Boolean.TRUE.equals(result.getSuccess())) {
            if (caseMapper.markCommandSuccess(riskCase.getId(), result.getCommandNo(),
                    LocalDateTime.now()) > 0) {
                boolean updated = Boolean.TRUE.equals(result.getUpdated());
                insertSystemNote(riskCase.getCaseNo(), "COMMAND_SUCCESS", "RESOLVED", "CLOSED",
                        updated ? "风控命令执行成功，业务状态已更新" : "风控命令执行完成，业务状态未变化",
                        evidence(result, updated));
            }
            return;
        }
        if (caseMapper.markCommandExecutionFailed(riskCase.getId(), result.getCommandNo(),
                LocalDateTime.now()) > 0) {
            insertSystemNote(riskCase.getCaseNo(), "COMMAND_FAILED", "RESOLVED", "RESOLVED",
                    "风控命令执行失败，等待重试或人工补偿", evidence(result, false));
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void markDeadLetter(String commandNo) {
        if (commandNo == null || commandNo.isBlank()) {
            throw new IllegalArgumentException("风控命令编号不能为空");
        }
        RiskCase riskCase = requireCaseByCommand(null, commandNo);
        if (caseMapper.markCommandDeadLetter(riskCase.getId(), commandNo, LocalDateTime.now()) > 0) {
            Map<String, Object> evidence = new HashMap<>();
            evidence.put("commandNo", commandNo);
            evidence.put("scene", riskCase.getScene());
            insertSystemNote(riskCase.getCaseNo(), "COMMAND_DEAD_LETTER", "RESOLVED", "RESOLVED",
                    "风控命令重试耗尽，进入死信队列", evidence);
        }
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
            caseMapper.markCommandFailed(caseId, message.getCommandNo(), LocalDateTime.now());
            throw e;
        }
    }

    private void requireSupportedCommand(String scene, String command) {
        boolean supported = switch (scene) {
            case "LISTING" -> List.of("APPROVE", "REJECT", "FREEZE").contains(command);
            case "MERCHANT" -> List.of("APPROVE", "REJECT", "FREEZE", "LIMIT").contains(command);
            case "WITHDRAW", "ORDER", "PAYMENT", "DELIVERY", "CONFIRM", "DISPUTE" ->
                    List.of("APPROVE", "FREEZE", "LIMIT", "DELAY_SETTLE").contains(command);
            default -> false;
        };
        if (!supported) {
            throw new IllegalArgumentException("案件场景不支持该处理命令");
        }
    }

    private RiskCase requireCase(String caseNo) {
        RiskCase riskCase = caseMapper.selectOne(new LambdaQueryWrapper<RiskCase>()
                .eq(RiskCase::getCaseNo, caseNo));
        if (riskCase == null) {
            throw new IllegalArgumentException("风控案件不存在");
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
            throw new IllegalArgumentException("风控命令不存在");
        }
        return riskCase;
    }

    private void requireResult(RiskCommandResultDTO result) {
        if (result == null || result.getCommandNo() == null || result.getCommandNo().isBlank()
                || result.getSuccess() == null) {
            throw new IllegalArgumentException("风控命令执行结果不合法");
        }
    }

    private Map<String, Object> evidence(RiskCommandResultDTO result, boolean updated) {
        Map<String, Object> evidence = new HashMap<>();
        evidence.put("commandNo", result.getCommandNo());
        evidence.put("scene", result.getScene());
        evidence.put("updated", updated);
        if (result.getMessage() != null && !result.getMessage().isBlank()) {
            evidence.put("message", result.getMessage());
        }
        return evidence;
    }

    private void insertSystemNote(String caseNo, String type, String before, String after,
                                  String content, Map<String, Object> evidence) {
        RiskCaseNote note = new RiskCaseNote();
        note.setCaseNo(caseNo);
        note.setNoteType(type);
        note.setOperatorId(0L);
        note.setOperatorName("RISK_COMMAND_SYSTEM");
        note.setContent(content);
        note.setBeforeStatus(before);
        note.setAfterStatus(after);
        note.setEvidenceJson(writeJson(evidence));
        note.setCreatedTime(LocalDateTime.now());
        noteMapper.insert(note);
    }

    private void requireOperator(Long operatorId, String operatorName) {
        if (operatorId == null || operatorName == null || operatorName.isBlank()) {
            throw new IllegalArgumentException("操作人不能为空");
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
            throw new IllegalArgumentException("案件业务编号不合法", e);
        }
    }

    private String nextCommandNo() {
        return "CMD-" + System.currentTimeMillis() + "-"
                + UUID.randomUUID().toString().replace("-", "");
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

    public record CaseDetail(RiskCase riskCase, List<RiskCaseNote> notes) {
    }
}
