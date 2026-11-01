package com.example.moneyagent.application;

import api.agent.money.MoneyErrorCodes;
import api.agent.money.MoneyIdGenerator;
import api.agent.money.MoneyProblemFamily;
import api.agent.money.MoneySecuritySupport;
import api.agent.money.contract.MoneyAgentException;
import api.agent.money.contract.MoneyInvestigationRequest;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.moneyagent.investigation.MoneyCheckpoint;
import com.example.moneyagent.investigation.MoneyInvestigationGraph;
import com.example.moneyagent.metrics.MoneyOperationalMetrics;
import com.example.moneyagent.persistence.entity.*;
import com.example.moneyagent.persistence.mapper.*;
import com.example.moneyagent.worker.MoneyTaskWorker;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
public class MoneyCaseService {

    private final MoneyCaseMapper caseMapper;
    private final MoneyTaskMapper taskMapper;
    private final MoneyReportMapper reportMapper;
    private final MoneyRequestMapper requestMapper;
    private final MoneyProjectionMapper projectionMapper;
    private final MoneyEventMapper eventMapper;
    private final MoneyGrantService grantService;
    private final MoneyInvestigationGraph investigationGraph;
    private final com.example.moneyagent.config.MoneyAgentProperties properties;
    private final MoneyOperationalMetrics metrics;
    private MoneyTaskWorker taskWorker;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;

    public void setTaskWorker(MoneyTaskWorker taskWorker) {
        this.taskWorker = taskWorker;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public MoneyCaseService(MoneyCaseMapper caseMapper,
                            MoneyTaskMapper taskMapper,
                            MoneyReportMapper reportMapper,
                            MoneyRequestMapper requestMapper,
                            MoneyProjectionMapper projectionMapper,
                            MoneyEventMapper eventMapper,
                            MoneyGrantService grantService,
                            MoneyInvestigationGraph investigationGraph,
                            com.example.moneyagent.config.MoneyAgentProperties properties,
                            @org.springframework.beans.factory.annotation.Autowired(required = false) MoneyOperationalMetrics metrics,
                            @org.springframework.beans.factory.annotation.Autowired(required = false) MoneyTaskWorker taskWorker,
                            @org.springframework.beans.factory.annotation.Autowired(required = false) org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.caseMapper = caseMapper;
        this.taskMapper = taskMapper;
        this.reportMapper = reportMapper;
        this.requestMapper = requestMapper;
        this.projectionMapper = projectionMapper;
        this.eventMapper = eventMapper;
        this.grantService = grantService;
        this.investigationGraph = investigationGraph;
        this.properties = properties != null ? properties : new com.example.moneyagent.config.MoneyAgentProperties();
        this.metrics = metrics != null ? metrics : new MoneyOperationalMetrics();
        this.taskWorker = taskWorker;
        this.transactionTemplate = transactionManager != null ? new TransactionTemplate(transactionManager) : null;
        this.objectMapper = new ObjectMapper();
    }

    public MoneyCaseService(MoneyCaseMapper caseMapper,
                            MoneyTaskMapper taskMapper,
                            MoneyReportMapper reportMapper,
                            MoneyRequestMapper requestMapper,
                            MoneyProjectionMapper projectionMapper,
                            MoneyEventMapper eventMapper,
                            MoneyGrantService grantService,
                            MoneyInvestigationGraph investigationGraph,
                            com.example.moneyagent.config.MoneyAgentProperties properties,
                            MoneyOperationalMetrics metrics,
                            MoneyTaskWorker taskWorker) {
        this(caseMapper, taskMapper, reportMapper, requestMapper, projectionMapper,
             eventMapper, grantService, investigationGraph, properties, metrics, taskWorker, null);
    }

    public MoneyCaseService(MoneyCaseMapper caseMapper,
                            MoneyTaskMapper taskMapper,
                            MoneyReportMapper reportMapper,
                            MoneyRequestMapper requestMapper,
                            MoneyProjectionMapper projectionMapper,
                            MoneyEventMapper eventMapper,
                            MoneyGrantService grantService,
                            MoneyInvestigationGraph investigationGraph,
                            com.example.moneyagent.config.MoneyAgentProperties properties,
                            MoneyOperationalMetrics metrics) {
        this(caseMapper, taskMapper, reportMapper, requestMapper, projectionMapper,
             eventMapper, grantService, investigationGraph, properties, metrics, null, null);
    }

    public MoneyCaseService(MoneyCaseMapper caseMapper,
                            MoneyTaskMapper taskMapper,
                            MoneyReportMapper reportMapper,
                            MoneyRequestMapper requestMapper,
                            MoneyProjectionMapper projectionMapper,
                            MoneyEventMapper eventMapper,
                            MoneyGrantService grantService,
                            MoneyInvestigationGraph investigationGraph,
                            com.example.moneyagent.config.MoneyAgentProperties properties) {
        this(caseMapper, taskMapper, reportMapper, requestMapper, projectionMapper,
             eventMapper, grantService, investigationGraph, properties, null, null, null);
    }

    public MoneyCaseService(MoneyCaseMapper caseMapper,
                            MoneyTaskMapper taskMapper,
                            MoneyReportMapper reportMapper,
                            MoneyRequestMapper requestMapper,
                            MoneyProjectionMapper projectionMapper,
                            MoneyEventMapper eventMapper,
                            MoneyGrantService grantService,
                            MoneyInvestigationGraph investigationGraph) {
        this(caseMapper, taskMapper, reportMapper, requestMapper, projectionMapper,
             eventMapper, grantService, investigationGraph, new com.example.moneyagent.config.MoneyAgentProperties(), null, null, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public CaseExecutionResult startInvestigation(MoneyInvestigationRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("MoneyInvestigationRequest 不能为空");
        }
        String scopeKey = request.getSubject() != null ? request.getSubject().getScopeKey() : null;
        if (scopeKey == null || scopeKey.isBlank()) {
            if (request.getSubject() != null && request.getSubject().getOrderNo() != null) {
                scopeKey = "ORDER:" + request.getSubject().getOrderNo();
            } else {
                scopeKey = "UNKNOWN";
            }
        }
        String problemFamily = request.getProblemFamily() != null ? request.getProblemFamily().name() : "PAYMENT_CONFIRMATION";
        String targetRef = null;
        if (request.getSubject() != null) {
            targetRef = request.getSubject().getAttemptNo() != null ? request.getSubject().getAttemptNo()
                    : (request.getSubject().getRefundNo() != null ? request.getSubject().getRefundNo()
                    : (request.getSubject().getDiffNo() != null ? request.getSubject().getDiffNo()
                    : request.getSubject().getOrderNo()));
        }
        if (targetRef == null) {
            targetRef = scopeKey;
        }
        String origin = request.getOrigin() != null ? request.getOrigin() : "DOMAIN_EVENT";
        String callerService = "mall-money-agent-service";
        String requestId = request.getRequestId();
        String explicitGrantRef = request.getDelegationRef();

        return createOrAttachCase(scopeKey, problemFamily, targetRef, origin, 0L, "SYSTEM",
                callerService, requestId, explicitGrantRef, request);
    }

    @Transactional(rollbackFor = Exception.class)
    public CaseExecutionResult createOrAttachCase(String scopeKey, String problemFamilyRaw, String targetRef,
                                                  String origin, Long actorUserId, String actorRole) {
        return createOrAttachCase(scopeKey, problemFamilyRaw, targetRef, origin, actorUserId, actorRole,
                null, null, null, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public CaseExecutionResult createOrAttachCase(String scopeKey, String problemFamilyRaw, String targetRef,
                                                  String origin, Long actorUserId, String actorRole,
                                                  String callerService, String requestId, String explicitGrantRef) {
        return createOrAttachCase(scopeKey, problemFamilyRaw, targetRef, origin, actorUserId, actorRole,
                callerService, requestId, explicitGrantRef, null);
    }

    public CaseExecutionResult createOrAttachCase(String scopeKey, String problemFamilyRaw, String targetRef,
                                                  String origin, Long actorUserId, String actorRole,
                                                  String callerService, String requestId, String explicitGrantRef,
                                                  MoneyInvestigationRequest originalRequest) {
        CaseExecutionResult result;
        if (transactionTemplate != null) {
            result = transactionTemplate.execute(status -> createOrAttachCaseInTx(scopeKey, problemFamilyRaw, targetRef, origin,
                    actorUserId, actorRole, callerService, requestId, explicitGrantRef, originalRequest));
        } else {
            result = createOrAttachCaseInTx(scopeKey, problemFamilyRaw, targetRef, origin,
                    actorUserId, actorRole, callerService, requestId, explicitGrantRef, originalRequest);
        }

        // 短事务提交后，若产生新就绪任务，由 Worker 异步认领执行，事务外执行绝不持有长锁与数据库连接，严禁同步阻塞 HTTP 主线程
        if (result != null && (result.isNew() || (result.taskEntity() != null && "SUBMITTED".equals(result.taskEntity().getState())))) {
            if (taskWorker != null) {
                CompletableFuture.runAsync(() -> taskWorker.runOneTask());
            }
        }
        return result;
    }

    @Transactional(rollbackFor = Exception.class)
    public CaseExecutionResult createOrAttachCaseInTx(String scopeKey, String problemFamilyRaw, String targetRef,
                                                      String origin, Long actorUserId, String actorRole,
                                                      String callerService, String requestId, String explicitGrantRef,
                                                      MoneyInvestigationRequest originalRequest) {
        String safeScopeKey = scopeKey != null ? scopeKey.trim() : "UNKNOWN";
        if (!properties.isEnabled()) {
            throw new MoneyAgentException(MoneyErrorCodes.MONEY_FORBIDDEN, "资金调查服务未启用 (money-agent.enabled=false)");
        }

        // 问题族严格白名单校验（首版仅允许 PAYMENT_CONFIRMATION, EXCESS_PAYMENT, REFUND_STATUS, RECON_MISMATCH，未知值拒绝）
        MoneyProblemFamily problemFamily = MoneyProblemFamily.fromString(problemFamilyRaw);
        String safeProblem = problemFamily.name();
        String safeTargetRef = targetRef != null ? targetRef.trim() : safeScopeKey;
        String safeCaller = callerService != null ? callerService : "mall-agent-service";
        Long safeUserId = actorUserId != null ? actorUserId : 0L;
        String safeRole = actorRole != null ? actorRole.toUpperCase() : "BUYER";
        LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC);

        // 1. 持久请求幂等校验 (Persistent Request Idempotency)
        String requestHash = MoneySecuritySupport.computeFingerprint(safeCaller + ":START:" + requestId + ":" + safeScopeKey + ":" + safeProblem + ":" + safeTargetRef);
        if (requestId != null && !requestId.isBlank()) {
            MoneyRequestEntity existingReq = requestMapper.selectOne(new LambdaQueryWrapper<MoneyRequestEntity>()
                    .eq(MoneyRequestEntity::getCallerId, safeCaller)
                    .eq(MoneyRequestEntity::getAction, "START_INVESTIGATION")
                    .eq(MoneyRequestEntity::getRequestId, requestId));
            if (existingReq != null) {
                if (!Objects.equals(existingReq.getRequestHash(), requestHash)) {
                    throw new MoneyAgentException(MoneyErrorCodes.MONEY_IDEMPOTENCY_CONFLICT,
                            "幂等冲突：相同 requestId (" + requestId + ") 但请求内容或主体不一致");
                }
                log.info("命中持久请求幂等记录, requestId={}, taskNo={}", requestId, existingReq.getTaskNo());
                if (metrics != null) metrics.recordIdempotencyHit();
                MoneyCaseEntity c = caseMapper.selectOne(new LambdaQueryWrapper<MoneyCaseEntity>().eq(MoneyCaseEntity::getCaseNo, existingReq.getCaseNo()));
                MoneyTaskEntity t = taskMapper.selectOne(new LambdaQueryWrapper<MoneyTaskEntity>().eq(MoneyTaskEntity::getTaskNo, existingReq.getTaskNo()));
                MoneyReportEntity r = c != null && c.getLatestReportNo() != null
                        ? reportMapper.selectOne(new LambdaQueryWrapper<MoneyReportEntity>().eq(MoneyReportEntity::getReportNo, c.getLatestReportNo()))
                        : null;
                return new CaseExecutionResult(c, t, r, false);
            }
        }

        // 2. 规范化数组序列化并计算活动去重键
        String canonicalDedupeInput = "[\"" + safeScopeKey + "\",\"" + safeProblem + "\",\"" + safeTargetRef + "\"]";
        String activeDedupeKey = MoneySecuritySupport.computeFingerprint(canonicalDedupeInput);

        // 3. 查找已有进行中案件（覆盖 OPEN, NEED_INPUT, NEED_REVIEW, WAITING_BIZ）
        MoneyCaseEntity existingCase = null;
        try {
            existingCase = caseMapper.selectActiveByDedupeKeyForUpdate(activeDedupeKey);
        } catch (Exception ignored) {
        }
        if (existingCase == null) {
            existingCase = caseMapper.selectOne(new LambdaQueryWrapper<MoneyCaseEntity>()
                    .eq(MoneyCaseEntity::getActiveDedupeKey, activeDedupeKey)
                    .in(MoneyCaseEntity::getState, List.of("OPEN", "NEED_INPUT", "NEED_REVIEW", "WAITING_BIZ")));
        }

        if (existingCase != null) {
            log.info("命中已有活跃资金案件, caseNo={}, currentTaskNo={}", existingCase.getCaseNo(), existingCase.getCurrentTaskNo());
            MoneyTaskEntity currentTask = existingCase.getCurrentTaskNo() != null
                    ? taskMapper.selectOne(new LambdaQueryWrapper<MoneyTaskEntity>().eq(MoneyTaskEntity::getTaskNo, existingCase.getCurrentTaskNo()))
                    : null;

            boolean isTerminal = currentTask != null && ("COMPLETED".equals(currentTask.getState())
                    || "FAILED".equals(currentTask.getState())
                    || "CANCELED".equals(currentTask.getState()));

            if (currentTask != null && !isTerminal) {
                // 复用未终态调查任务并建立 task_access 访问绑定
                grantService.createTaskAccess(currentTask.getTaskNo(), existingCase.getGrantRef(), safeCaller,
                        safeRole + ":" + safeUserId, nowUtc.plusDays(1));
                MoneyReportEntity latestReport = existingCase.getLatestReportNo() != null
                        ? reportMapper.selectOne(new LambdaQueryWrapper<MoneyReportEntity>().eq(MoneyReportEntity::getReportNo, existingCase.getLatestReportNo()))
                        : null;
                saveRequestRecord(safeCaller, requestId, requestHash, "", existingCase.getGrantRef(),
                        existingCase.getCaseNo(), currentTask.getTaskNo(), nowUtc,
                        "{\"caseNo\":\"" + existingCase.getCaseNo() + "\",\"taskNo\":\"" + currentTask.getTaskNo() + "\"}");
                return new CaseExecutionResult(existingCase, currentTask, latestReport, false);
            } else {
                // 终态任务后的重分析：在同一案件下新建 Task 与报告版本
                log.info("已有案件任务已处于终态，开启重分析新 Task, caseNo={}", existingCase.getCaseNo());
                String newTaskNo = MoneyIdGenerator.generateTaskNo();
                String contextId = MoneyIdGenerator.generateContextId();
                MoneyTaskEntity newTask = MoneyTaskEntity.builder()
                        .taskNo(newTaskNo)
                        .caseNo(existingCase.getCaseNo())
                        .contextId(contextId)
                        .executionGrantRef(existingCase.getGrantRef())
                        .executionScopeJson(existingCase.getScopeKey())
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
                if (metrics != null) metrics.recordTaskStarted(safeProblem);

                existingCase.setCurrentTaskNo(newTaskNo);
                existingCase.setState("OPEN");
                existingCase.setUpdatedAt(nowUtc);
                existingCase.setVersion(existingCase.getVersion() != null ? existingCase.getVersion() + 1 : 1L);
                caseMapper.updateById(existingCase);

                grantService.createTaskAccess(newTaskNo, existingCase.getGrantRef(), safeCaller,
                        safeRole + ":" + safeUserId, nowUtc.plusDays(1));

                saveInitialProjections(existingCase.getCaseNo(), existingCase.getOrderNo(), safeProblem, safeTargetRef,
                        existingCase.getGrantRef(), existingCase.getScopeKey(), nowUtc);

                saveRequestRecord(safeCaller, requestId, requestHash, "", existingCase.getGrantRef(),
                        existingCase.getCaseNo(), newTaskNo, nowUtc,
                        "{\"caseNo\":\"" + existingCase.getCaseNo() + "\",\"taskNo\":\"" + newTaskNo + "\"}");
                return new CaseExecutionResult(existingCase, newTask, null, false);
            }
        }

        // 4. 检查是否有已结束案件并关联 previousCaseNo
        MoneyCaseEntity closedCase = caseMapper.selectOne(new LambdaQueryWrapper<MoneyCaseEntity>()
                .eq(MoneyCaseEntity::getScopeKey, safeScopeKey)
                .eq(MoneyCaseEntity::getProblemFamily, safeProblem)
                .in(MoneyCaseEntity::getState, List.of("RESOLVED", "CLOSED", "CANCELED"))
                .orderByDesc(MoneyCaseEntity::getId)
                .last("LIMIT 1"));
        String previousCaseNo = closedCase != null ? closedCase.getCaseNo() : null;

        // 5. 严格遵循业务编号规范：MC / MT + 32 位大写十六进制 (总长 34)
        if (!properties.isAcceptNewCases()) {
            throw new MoneyAgentException(MoneyErrorCodes.MONEY_FORBIDDEN, "资金调查服务已暂停受理新案件 (money-agent.accept-new-cases=false)");
        }
        String caseNo = MoneyIdGenerator.generateCaseNo();
        String taskNo = MoneyIdGenerator.generateTaskNo();
        String contextId = MoneyIdGenerator.generateContextId();
        String grantRef = (explicitGrantRef != null && !explicitGrantRef.isBlank())
                ? explicitGrantRef
                : MoneyIdGenerator.generateGrantNo();
        String orderNo = extractOrderNo(safeScopeKey, safeTargetRef);

        Long ownerStaff = ("FINANCE".equalsIgnoreCase(safeRole) || "STAFF".equalsIgnoreCase(safeRole))
                ? safeUserId : null;

        // 6. 注册持久授权记录 t_money_grant
        String scopeJson = "{\"scopeKey\":\"" + safeScopeKey + "\",\"orderNo\":\"" + (orderNo != null ? orderNo : "") + "\"}";
        String allowedActionsJson = "[\"INVESTIGATE\",\"GET_CASE\",\"PROVIDE_INPUT\",\"CANCEL\"]";
        String allowedReceiversJson = "[\"mall-money-agent-service\",\"mall-order-service\",\"mall-user-service\"]";
        grantService.registerGrant(grantRef, safeCaller, safeRole, String.valueOf(safeUserId),
                scopeJson, allowedActionsJson, allowedReceiversJson, nowUtc.plusDays(1));

        // 7. 建立新案件记录
        MoneyCaseEntity newCase = MoneyCaseEntity.builder()
                .caseNo(caseNo)
                .scopeKey(safeScopeKey)
                .problemFamily(safeProblem)
                .targetRef(safeTargetRef)
                .activeDedupeKey(activeDedupeKey)
                .previousCaseNo(previousCaseNo)
                .orderNo(orderNo)
                .origin(origin != null ? origin : "CUSTOMER")
                .state("OPEN")
                .outcome("UNDETERMINED")
                .severity("NORMAL")
                .ownerStaffId(ownerStaff)
                .currentTaskNo(taskNo)
                .grantRef(grantRef)
                .eventSeq(1L)
                .version(1L)
                .createdAt(nowUtc)
                .updatedAt(nowUtc)
                .build();
        caseMapper.insert(newCase);

        // 8. 固化执行范围 executionScopeJson
        Map<String, Object> executionScopeMap = new LinkedHashMap<>();
        executionScopeMap.put("scopeKey", safeScopeKey);
        executionScopeMap.put("orderNo", orderNo);
        executionScopeMap.put("targetRef", safeTargetRef);
        executionScopeMap.put("actorUserId", safeUserId);
        executionScopeMap.put("actorRole", safeRole);
        executionScopeMap.put("allowedActions", List.of("INVESTIGATE", "READ"));

        String executionScopeJson;
        try {
            executionScopeJson = objectMapper.writeValueAsString(executionScopeMap);
        } catch (Exception e) {
            executionScopeJson = "{\"scopeKey\":\"" + safeScopeKey + "\"}";
        }
        String executionScopeHash = MoneySecuritySupport.computeFingerprint(executionScopeJson);

        // 初始化包含全链路追踪信息的 Checkpoint
        MoneyInvestigationRequest.CallerContext cc = originalRequest != null ? originalRequest.getCallerContext() : null;
        MoneyCheckpoint initialCp = MoneyCheckpoint.builder()
                .traceId(cc != null && cc.getTraceId() != null && !cc.getTraceId().isBlank() ? cc.getTraceId() : (requestId != null ? "TRACE_" + requestId : "TRACE_" + taskNo))
                .customerRunNo(cc != null ? cc.getCustomerRunNo() : null)
                .dispatchNo(cc != null ? cc.getDispatchNo() : null)
                .build();
        String initialCheckpointJson = null;
        try {
            initialCheckpointJson = objectMapper.writeValueAsString(initialCp);
        } catch (Exception ignored) {}

        // 9. 插入调查任务 t_money_task
        MoneyTaskEntity newTask = MoneyTaskEntity.builder()
                .taskNo(taskNo)
                .caseNo(caseNo)
                .contextId(contextId)
                .executionGrantRef(grantRef)
                .executionScopeJson(executionScopeJson)
                .executionScopeHash(executionScopeHash)
                .checkpointJson(initialCheckpointJson)
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
        if (metrics != null) metrics.recordTaskStarted(safeProblem);

        // 10. 建立 t_money_task_access
        grantService.createTaskAccess(taskNo, grantRef, safeCaller, safeRole + ":" + safeUserId, nowUtc.plusDays(1));

        // 11. 记录 Case 创建事件
        MoneyEventEntity createEvent = MoneyEventEntity.builder()
                .eventNo("EVT_" + MoneyIdGenerator.generateDispatchNo().substring(2))
                .caseNo(caseNo)
                .caseSeq(1L)
                .taskNo(taskNo)
                .eventType("CASE_CREATED")
                .payloadRefJson("{\"origin\":\"" + newCase.getOrigin() + "\",\"problemFamily\":\"" + safeProblem + "\"}")
                .visibility("ALL")
                .createdAt(nowUtc)
                .build();
        eventMapper.insert(createEvent);

        // 12. 写入初始受理进度投影 (BUYER & FINANCE)
        saveInitialProjections(caseNo, orderNo, safeProblem, safeTargetRef, grantRef, safeScopeKey, nowUtc);

        // 13. 记录请求幂等表 t_money_request
        saveRequestRecord(safeCaller, requestId, requestHash, executionScopeHash, grantRef, caseNo, taskNo, nowUtc,
                "{\"caseNo\":\"" + caseNo + "\",\"taskNo\":\"" + taskNo + "\",\"contextId\":\"" + contextId + "\"}");

        return new CaseExecutionResult(newCase, newTask, null, true);
    }

    private void saveRequestRecord(String safeCaller, String requestId, String requestHash, String executionScopeHash,
                                   String grantRef, String caseNo, String taskNo, LocalDateTime nowUtc, String receiptJson) {
        if (requestId != null && !requestId.isBlank()) {
            try {
                MoneyRequestEntity reqEntity = MoneyRequestEntity.builder()
                        .callerId(safeCaller)
                        .action("START_INVESTIGATION")
                        .requestId(requestId)
                        .requestHash(requestHash)
                        .subjectScopeHash(executionScopeHash)
                        .grantRef(grantRef)
                        .caseNo(caseNo)
                        .taskNo(taskNo)
                        .state("COMPLETED")
                        .receiptJson(receiptJson)
                        .createdAt(nowUtc)
                        .updatedAt(nowUtc)
                        .build();
                requestMapper.insert(reqEntity);
            } catch (Exception e) {
                log.warn("写入 t_money_request 异常", e);
            }
        }
    }

    private void saveInitialProjections(String caseNo, String orderNo, String safeProblem, String safeTargetRef,
                                        String grantRef, String safeScopeKey, LocalDateTime nowUtc) {
        try {
            Map<String, Object> buyerData = new LinkedHashMap<>();
            buyerData.put("caseNo", caseNo);
            buyerData.put("orderNo", orderNo != null ? orderNo : "");
            buyerData.put("problemFamily", safeProblem);
            buyerData.put("targetRef", safeTargetRef);
            buyerData.put("resolutionOutcome", "INVESTIGATING");
            buyerData.put("publicSummary", "资金核查任务已受理，正在排队调查");
            buyerData.put("projectionRevision", 1L);
            String buyerJson = objectMapper.writeValueAsString(buyerData);

            MoneyProjectionEntity buyerProj = MoneyProjectionEntity.builder()
                    .caseNo(caseNo)
                    .audienceScope("BUYER")
                    .revision(1L)
                    .publicJson(buyerJson)
                    .contentHash(MoneySecuritySupport.computeFingerprint(buyerJson))
                    .latestCaseEventSeq(1L)
                    .policyVersion("POLICY_V1")
                    .createdAt(nowUtc)
                    .updatedAt(nowUtc)
                    .version(1L)
                    .build();
            projectionMapper.insert(buyerProj);

            Map<String, Object> financeData = new LinkedHashMap<>(buyerData);
            financeData.put("grantRef", grantRef);
            financeData.put("scopeKey", safeScopeKey);
            String financeJson = objectMapper.writeValueAsString(financeData);

            MoneyProjectionEntity financeProj = MoneyProjectionEntity.builder()
                    .caseNo(caseNo)
                    .audienceScope("FINANCE")
                    .revision(1L)
                    .publicJson(financeJson)
                    .contentHash(MoneySecuritySupport.computeFingerprint(financeJson))
                    .latestCaseEventSeq(1L)
                    .policyVersion("POLICY_V1")
                    .createdAt(nowUtc)
                    .updatedAt(nowUtc)
                    .version(1L)
                    .build();
            projectionMapper.insert(financeProj);
        } catch (Exception e) {
            log.warn("写入初始受理进度投影异常, caseNo={}", caseNo, e);
        }
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

    public MoneyProjectionEntity getProjection(String caseNo, String audienceScope) {
        if (caseNo == null || caseNo.isBlank()) return null;
        String scope = audienceScope != null ? audienceScope.toUpperCase() : "BUYER";
        return projectionMapper.selectOne(new LambdaQueryWrapper<MoneyProjectionEntity>()
                .eq(MoneyProjectionEntity::getCaseNo, caseNo.trim())
                .eq(MoneyProjectionEntity::getAudienceScope, scope)
                .orderByDesc(MoneyProjectionEntity::getRevision)
                .last("LIMIT 1"));
    }

    public Page<MoneyCaseEntity> listCases(int page, int size, String problemFamily, String state, String severity, Long ownerStaffId) {
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
        if (ownerStaffId != null) {
            query.eq(MoneyCaseEntity::getOwnerStaffId, ownerStaffId);
        }
        query.orderByDesc(MoneyCaseEntity::getId);
        return caseMapper.selectPage(new Page<>(page, size), query);
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean assignStaff(String caseNo, Long staffId, Long expectedCaseVersion) {
        MoneyCaseEntity caseEntity = caseMapper.selectOne(new LambdaQueryWrapper<MoneyCaseEntity>()
                .eq(MoneyCaseEntity::getCaseNo, caseNo));
        if (caseEntity == null) return false;

        if (expectedCaseVersion != null && !expectedCaseVersion.equals(caseEntity.getVersion())) {
            throw new IllegalStateException("案件版本冲突，请刷新后重试: expected=" + expectedCaseVersion + ", actual=" + caseEntity.getVersion());
        }

        caseEntity.setOwnerStaffId(staffId);
        caseEntity.setVersion(caseEntity.getVersion() + 1);
        caseEntity.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        return caseMapper.updateById(caseEntity) > 0;
    }

    public CaseExecutionResult reinvestigate(String caseNo, String reason, String newFacts, Long staffId) {
        CaseExecutionResult result;
        if (transactionTemplate != null) {
            result = transactionTemplate.execute(status -> reinvestigateInTx(caseNo, reason, newFacts, staffId));
        } else {
            result = reinvestigateInTx(caseNo, reason, newFacts, staffId);
        }

        // 短事务提交后，若产生新就绪任务，由 Worker 异步认领执行，严禁同步阻塞 HTTP 主线程
        if (result != null && result.taskEntity() != null && "SUBMITTED".equals(result.taskEntity().getState())) {
            if (taskWorker != null) {
                CompletableFuture.runAsync(() -> taskWorker.runOneTask());
            }
        }
        return result;
    }

    @Transactional(rollbackFor = Exception.class)
    public CaseExecutionResult reinvestigateInTx(String caseNo, String reason, String newFacts, Long staffId) {
        MoneyCaseEntity caseEntity = caseMapper.selectOne(new LambdaQueryWrapper<MoneyCaseEntity>()
                .eq(MoneyCaseEntity::getCaseNo, caseNo));
        if (caseEntity == null) {
            throw new IllegalArgumentException("案件不存在: " + caseNo);
        }

        if (caseEntity.getCurrentTaskNo() != null) {
            MoneyTaskEntity currentTask = taskMapper.selectOne(new LambdaQueryWrapper<MoneyTaskEntity>()
                    .eq(MoneyTaskEntity::getTaskNo, caseEntity.getCurrentTaskNo()));
            if (currentTask != null && List.of("SUBMITTED", "WORKING").contains(currentTask.getState())) {
                throw new IllegalStateException("CASE_TASK_BUSY: 当前已有执行中的调查任务，无法重复发起");
            }
        }

        LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC);
        String newTaskNo = MoneyIdGenerator.generateTaskNo();
        String contextId = MoneyIdGenerator.generateContextId();

        MoneyTaskEntity newTask = MoneyTaskEntity.builder()
                .taskNo(newTaskNo)
                .caseNo(caseNo)
                .contextId(contextId)
                .executionGrantRef(caseEntity.getGrantRef())
                .executionScopeJson("{\"reinvestigateReason\":\"" + (reason != null ? reason : "") + "\"}")
                .executionScopeHash(MoneySecuritySupport.computeFingerprint(caseEntity.getScopeKey() + ":" + newTaskNo))
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

        caseEntity.setCurrentTaskNo(newTaskNo);
        caseEntity.setState("OPEN");
        caseEntity.setVersion(caseEntity.getVersion() + 1);
        caseEntity.setUpdatedAt(nowUtc);
        caseMapper.updateById(caseEntity);

        saveInitialProjections(caseNo, caseEntity.getOrderNo(), caseEntity.getProblemFamily(),
                caseEntity.getTargetRef(), caseEntity.getGrantRef(), caseEntity.getScopeKey(), nowUtc);

        return new CaseExecutionResult(caseEntity, newTask, null, false);
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean closeCase(String caseNo, String reason, Long staffId) {
        MoneyCaseEntity caseEntity = caseMapper.selectOne(new LambdaQueryWrapper<MoneyCaseEntity>()
                .eq(MoneyCaseEntity::getCaseNo, caseNo));
        if (caseEntity == null) return false;

        caseEntity.setState("CLOSED");
        caseEntity.setActiveDedupeKey(null);
        caseEntity.setCloseReason(reason);
        if (staffId != null && staffId > 0) {
            caseEntity.setOwnerStaffId(staffId);
        }
        caseEntity.setClosedAt(LocalDateTime.now(ZoneOffset.UTC));
        caseEntity.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        caseEntity.setVersion(caseEntity.getVersion() + 1);
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
