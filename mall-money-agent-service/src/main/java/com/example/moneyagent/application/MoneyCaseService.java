package com.example.moneyagent.application;

import api.agent.money.MoneySecuritySupport;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.moneyagent.investigation.MoneyInvestigationGraph;
import com.example.moneyagent.persistence.entity.*;
import com.example.moneyagent.persistence.mapper.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Slf4j
@Service
public class MoneyCaseService {

    private final MoneyCaseMapper caseMapper;
    private final MoneyTaskMapper taskMapper;
    private final MoneyReportMapper reportMapper;
    private final MoneyRequestMapper requestMapper;
    private final MoneyProjectionMapper projectionMapper;
    private final MoneyEventMapper eventMapper;
    private final MoneyInvestigationGraph investigationGraph;

    public MoneyCaseService(MoneyCaseMapper caseMapper,
                            MoneyTaskMapper taskMapper,
                            MoneyReportMapper reportMapper,
                            MoneyRequestMapper requestMapper,
                            MoneyProjectionMapper projectionMapper,
                            MoneyEventMapper eventMapper,
                            MoneyInvestigationGraph investigationGraph) {
        this.caseMapper = caseMapper;
        this.taskMapper = taskMapper;
        this.reportMapper = reportMapper;
        this.requestMapper = requestMapper;
        this.projectionMapper = projectionMapper;
        this.eventMapper = eventMapper;
        this.investigationGraph = investigationGraph;
    }

    @Transactional(rollbackFor = Exception.class)
    public CaseExecutionResult createOrAttachCase(String scopeKey, String problemFamily, String targetRef,
                                                  String origin, Long actorUserId, String actorRole) {
        String safeScopeKey = scopeKey != null ? scopeKey.trim() : "UNKNOWN";
        String safeProblem = problemFamily != null ? problemFamily.trim().toUpperCase() : "PAYMENT_CONFIRMATION";
        String safeTargetRef = targetRef != null ? targetRef.trim() : safeScopeKey;

        // 原子去重键：SHA256(scopeKey:problemFamily:targetRef)
        String dedupeRaw = safeScopeKey + ":" + safeProblem + ":" + safeTargetRef;
        String activeDedupeKey = MoneySecuritySupport.computeFingerprint(dedupeRaw);

        // 查找已有进行中案件
        MoneyCaseEntity existingCase = caseMapper.selectOne(new LambdaQueryWrapper<MoneyCaseEntity>()
                .eq(MoneyCaseEntity::getActiveDedupeKey, activeDedupeKey)
                .eq(MoneyCaseEntity::getState, "OPEN"));

        if (existingCase != null) {
            log.info("命中已有活跃资金案件, caseNo={}, currentTaskNo={}", existingCase.getCaseNo(), existingCase.getCurrentTaskNo());
            MoneyTaskEntity currentTask = existingCase.getCurrentTaskNo() != null
                    ? taskMapper.selectOne(new LambdaQueryWrapper<MoneyTaskEntity>().eq(MoneyTaskEntity::getTaskNo, existingCase.getCurrentTaskNo()))
                    : null;
            MoneyReportEntity latestReport = existingCase.getLatestReportNo() != null
                    ? reportMapper.selectOne(new LambdaQueryWrapper<MoneyReportEntity>().eq(MoneyReportEntity::getReportNo, existingCase.getLatestReportNo()))
                    : null;
            return new CaseExecutionResult(existingCase, currentTask, latestReport, false);
        }

        // 规范案件与任务编号（统一采用 MC / MT 前缀）
        String uuidHex = UUID.randomUUID().toString().replace("-", "");
        String caseNo = "MC" + uuidHex.substring(0, 30);
        String taskNo = "MT" + uuidHex.substring(0, 30);
        String orderNo = extractOrderNo(safeScopeKey, safeTargetRef);

        LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC);

        // 买家发起的调查不自动成为财务负责人（财务负责人仅在后台指定分配或财务人员自建时记录）
        Long ownerStaff = ("FINANCE".equalsIgnoreCase(actorRole) || "STAFF".equalsIgnoreCase(actorRole))
                ? actorUserId : null;

        MoneyCaseEntity newCase = MoneyCaseEntity.builder()
                .caseNo(caseNo)
                .scopeKey(safeScopeKey)
                .problemFamily(safeProblem)
                .targetRef(safeTargetRef)
                .activeDedupeKey(activeDedupeKey)
                .orderNo(orderNo)
                .origin(origin != null ? origin : "CUSTOMER")
                .state("OPEN")
                .outcome("UNDETERMINED")
                .severity("NORMAL")
                .ownerStaffId(ownerStaff)
                .currentTaskNo(taskNo)
                .grantRef("GRANT_" + uuidHex.substring(0, 16))
                .eventSeq(1L)
                .version(1L)
                .createdAt(nowUtc)
                .updatedAt(nowUtc)
                .build();
        caseMapper.insert(newCase);

        MoneyTaskEntity newTask = MoneyTaskEntity.builder()
                .taskNo(taskNo)
                .caseNo(caseNo)
                .contextId("CTX_" + uuidHex.substring(0, 16))
                .executionGrantRef(newCase.getGrantRef())
                .executionScopeJson("{}")
                .executionScopeHash(MoneySecuritySupport.computeFingerprint(safeScopeKey))
                .state("SUBMITTED")
                .planRounds(0)
                .toolCalls(0)
                .inputTokens(0L)
                .outputTokens(0L)
                .costMicrounits(0L)
                .version(1L)
                .createdAt(nowUtc)
                .updatedAt(nowUtc)
                .build();
        taskMapper.insert(newTask);

        // 记录 Case 创建事件
        MoneyEventEntity createEvent = MoneyEventEntity.builder()
                .eventNo("EVT_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24))
                .caseNo(caseNo)
                .caseSeq(1L)
                .taskNo(taskNo)
                .eventType("CASE_CREATED")
                .payloadRefJson("{\"origin\":\"" + newCase.getOrigin() + "\",\"problemFamily\":\"" + safeProblem + "\"}")
                .visibility("ALL")
                .createdAt(nowUtc)
                .build();
        eventMapper.insert(createEvent);

        // 触发调查 Graph 计算
        MoneyReportEntity report = investigationGraph.executeInvestigation(newCase, newTask);

        // 生成并持久化受众投影 (Projection)
        if (report != null) {
            MoneyProjectionEntity projection = MoneyProjectionEntity.builder()
                    .caseNo(caseNo)
                    .audienceScope("BUYER")
                    .revision(1L)
                    .reportNo(report.getReportNo())
                    .publicJson("{\"outcome\":\"" + report.getOutcome() + "\",\"summary\":\"调查取证完成\"}")
                    .contentHash(report.getReportHash())
                    .latestCaseEventSeq(2L)
                    .policyVersion("V1")
                    .createdAt(nowUtc)
                    .updatedAt(nowUtc)
                    .version(1L)
                    .build();
            projectionMapper.insert(projection);
        }

        return new CaseExecutionResult(newCase, newTask, report, true);
    }

    public CaseDetailResult getCaseDetail(String caseNo) {
        if (caseNo == null || caseNo.isBlank()) return null;
        MoneyCaseEntity caseEntity = caseMapper.selectOne(new LambdaQueryWrapper<MoneyCaseEntity>()
                .eq(MoneyCaseEntity::getCaseNo, caseNo.trim()));
        if (caseEntity == null) return null;

        MoneyTaskEntity currentTask = caseEntity.getCurrentTaskNo() != null
                ? taskMapper.selectOne(new LambdaQueryWrapper<MoneyTaskEntity>().eq(MoneyTaskEntity::getTaskNo, caseEntity.getCurrentTaskNo()))
                : null;
        MoneyReportEntity latestReport = caseEntity.getLatestReportNo() != null
                ? reportMapper.selectOne(new LambdaQueryWrapper<MoneyReportEntity>().eq(MoneyReportEntity::getReportNo, caseEntity.getLatestReportNo()))
                : null;

        return new CaseDetailResult(caseEntity, currentTask, latestReport);
    }

    public Page<MoneyCaseEntity> listCases(int page, int size, String problemFamily, String state, String severity) {
        LambdaQueryWrapper<MoneyCaseEntity> query = new LambdaQueryWrapper<>();
        if (problemFamily != null && !problemFamily.isBlank()) {
            query.eq(MoneyCaseEntity::getProblemFamily, problemFamily);
        }
        if (state != null && !state.isBlank()) {
            query.eq(MoneyCaseEntity::getState, state);
        }
        if (severity != null && !severity.isBlank()) {
            query.eq(MoneyCaseEntity::getSeverity, severity);
        }
        query.orderByDesc(MoneyCaseEntity::getId);
        return caseMapper.selectPage(new Page<>(page, size), query);
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean closeCase(String caseNo, String reason, Long staffId) {
        MoneyCaseEntity caseEntity = caseMapper.selectOne(new LambdaQueryWrapper<MoneyCaseEntity>()
                .eq(MoneyCaseEntity::getCaseNo, caseNo));
        if (caseEntity == null) return false;

        caseEntity.setState("CLOSED");
        caseEntity.setActiveDedupeKey(null); // 释放活跃去重键
        caseEntity.setCloseReason(reason);
        caseEntity.setClosedAt(LocalDateTime.now(ZoneOffset.UTC));
        caseEntity.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        return caseMapper.updateById(caseEntity) > 0;
    }

    private String extractOrderNo(String scopeKey, String targetRef) {
        if (scopeKey != null && scopeKey.startsWith("ORDER:")) {
            return scopeKey.substring(6).trim();
        }
        if (targetRef != null && targetRef.startsWith("T") && !targetRef.startsWith("TASK_") && !targetRef.startsWith("MT")) {
            return targetRef.trim();
        }
        return null;
    }

    public record CaseExecutionResult(MoneyCaseEntity caseEntity, MoneyTaskEntity taskEntity, MoneyReportEntity reportEntity, boolean isNew) {}
    public record CaseDetailResult(MoneyCaseEntity caseEntity, MoneyTaskEntity taskEntity, MoneyReportEntity reportEntity) {}
}
