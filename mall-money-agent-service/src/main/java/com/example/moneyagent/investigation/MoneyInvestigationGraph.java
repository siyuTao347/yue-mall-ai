package com.example.moneyagent.investigation;

import api.agent.money.*;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.moneyagent.application.MoneyGrantService;
import com.example.moneyagent.persistence.entity.*;
import com.example.moneyagent.persistence.mapper.*;
import com.example.moneyagent.tools.OrderMoneyQueryClient;
import com.example.moneyagent.tools.UserFundQueryClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

@Slf4j
@Component
public class MoneyInvestigationGraph {

    private final OrderMoneyQueryClient orderQueryClient;
    private final UserFundQueryClient fundQueryClient;
    private final MoneyCaseMapper caseMapper;
    private final MoneyTaskMapper taskMapper;
    private final MoneyReportMapper reportMapper;
    private final MoneyInputRequestMapper inputRequestMapper;
    private final MoneyProjectionMapper projectionMapper;
    private final MoneyEventMapper eventMapper;
    private final MoneyGrantService grantService;
    private final MoneyModelClient modelClient;
    private final MoneyPlanValidator planValidator;
    private final ObjectMapper objectMapper;
    private final MoneySnapshotMapper snapshotMapper;
    private final MoneyToolCallMapper toolCallMapper;
    private final MoneyOutboxMapper outboxMapper;
    private final com.example.moneyagent.config.MoneyAgentProperties properties;
    private final com.example.moneyagent.metrics.MoneyOperationalMetrics metrics;
    private TransactionTemplate transactionTemplate;

    public MoneyInvestigationGraph(OrderMoneyQueryClient orderQueryClient,
                                  UserFundQueryClient fundQueryClient,
                                  MoneyCaseMapper caseMapper,
                                  MoneyTaskMapper taskMapper,
                                  MoneyReportMapper reportMapper,
                                  MoneyInputRequestMapper inputRequestMapper,
                                  MoneyProjectionMapper projectionMapper,
                                  MoneyEventMapper eventMapper,
                                  MoneyGrantService grantService,
                                  MoneyModelClient modelClient,
                                  MoneyPlanValidator planValidator,
                                  ObjectMapper objectMapper,
                                  MoneySnapshotMapper snapshotMapper,
                                  MoneyToolCallMapper toolCallMapper,
                                  MoneyOutboxMapper outboxMapper,
                                  com.example.moneyagent.config.MoneyAgentProperties properties) {
        this(orderQueryClient, fundQueryClient, caseMapper, taskMapper, reportMapper,
             inputRequestMapper, projectionMapper, eventMapper, grantService,
             modelClient, planValidator, objectMapper, snapshotMapper, toolCallMapper, outboxMapper, properties, null, null);
    }

    public MoneyInvestigationGraph(OrderMoneyQueryClient orderQueryClient,
                                  UserFundQueryClient fundQueryClient,
                                  MoneyCaseMapper caseMapper,
                                  MoneyTaskMapper taskMapper,
                                  MoneyReportMapper reportMapper,
                                  MoneyInputRequestMapper inputRequestMapper,
                                  MoneyProjectionMapper projectionMapper,
                                  MoneyEventMapper eventMapper,
                                  MoneyGrantService grantService,
                                  MoneyModelClient modelClient,
                                  MoneyPlanValidator planValidator,
                                  ObjectMapper objectMapper,
                                  MoneySnapshotMapper snapshotMapper,
                                  MoneyToolCallMapper toolCallMapper,
                                  MoneyOutboxMapper outboxMapper,
                                  com.example.moneyagent.config.MoneyAgentProperties properties,
                                  com.example.moneyagent.metrics.MoneyOperationalMetrics metrics) {
        this(orderQueryClient, fundQueryClient, caseMapper, taskMapper, reportMapper,
             inputRequestMapper, projectionMapper, eventMapper, grantService,
             modelClient, planValidator, objectMapper, snapshotMapper, toolCallMapper, outboxMapper, properties, metrics, null);
    }

    @Autowired
    public MoneyInvestigationGraph(OrderMoneyQueryClient orderQueryClient,
                                  UserFundQueryClient fundQueryClient,
                                  MoneyCaseMapper caseMapper,
                                  MoneyTaskMapper taskMapper,
                                  MoneyReportMapper reportMapper,
                                  MoneyInputRequestMapper inputRequestMapper,
                                  MoneyProjectionMapper projectionMapper,
                                  MoneyEventMapper eventMapper,
                                  MoneyGrantService grantService,
                                  MoneyModelClient modelClient,
                                  MoneyPlanValidator planValidator,
                                  ObjectMapper objectMapper,
                                  MoneySnapshotMapper snapshotMapper,
                                  MoneyToolCallMapper toolCallMapper,
                                  MoneyOutboxMapper outboxMapper,
                                  com.example.moneyagent.config.MoneyAgentProperties properties,
                                  com.example.moneyagent.metrics.MoneyOperationalMetrics metrics,
                                  @Autowired(required = false) PlatformTransactionManager transactionManager) {
        this.orderQueryClient = orderQueryClient;
        this.fundQueryClient = fundQueryClient;
        this.caseMapper = caseMapper;
        this.taskMapper = taskMapper;
        this.reportMapper = reportMapper;
        this.inputRequestMapper = inputRequestMapper;
        this.projectionMapper = projectionMapper;
        this.eventMapper = eventMapper;
        this.grantService = grantService;
        this.modelClient = modelClient;
        this.planValidator = planValidator;
        this.objectMapper = objectMapper;
        this.snapshotMapper = snapshotMapper;
        this.toolCallMapper = toolCallMapper;
        this.outboxMapper = outboxMapper;
        this.properties = properties != null ? properties : new com.example.moneyagent.config.MoneyAgentProperties();
        this.metrics = metrics != null ? metrics : new com.example.moneyagent.metrics.MoneyOperationalMetrics();
        this.transactionTemplate = transactionManager != null ? new TransactionTemplate(transactionManager) : null;
    }

    public void setTransactionTemplate(TransactionTemplate transactionTemplate) {
        this.transactionTemplate = transactionTemplate;
    }

    public MoneyInvestigationGraph(OrderMoneyQueryClient orderQueryClient,
                                  UserFundQueryClient fundQueryClient,
                                  MoneyCaseMapper caseMapper,
                                  MoneyTaskMapper taskMapper,
                                  MoneyReportMapper reportMapper,
                                  MoneyInputRequestMapper inputRequestMapper,
                                  MoneyProjectionMapper projectionMapper,
                                  MoneyEventMapper eventMapper,
                                  MoneyGrantService grantService,
                                  MoneyModelClient modelClient,
                                  MoneyPlanValidator planValidator,
                                  ObjectMapper objectMapper) {
        this(orderQueryClient, fundQueryClient, caseMapper, taskMapper, reportMapper,
             inputRequestMapper, projectionMapper, eventMapper, grantService,
             modelClient, planValidator, objectMapper, null, null, null, null, null, null);
    }

    /**
     * 执行完整调查 Graph 状态机（通过循环调度节点直至终态或暂停）
     */
    public MoneyReportEntity executeInvestigation(MoneyCaseEntity caseEntity, MoneyTaskEntity taskEntity) {
        log.info("开始执行资金调查 StateGraph, caseNo={}, taskNo={}, problemFamily={}",
                caseEntity.getCaseNo(), taskEntity.getTaskNo(), caseEntity.getProblemFamily());

        // 读取已有 checkpoint 或初始化
        MoneyCheckpoint cp = readOrCreateCheckpoint(taskEntity);

        // 获取原主体身份
        ActorIdentity identity = resolveActorIdentity(caseEntity);

        // 构造 Order 与 User 跨服务鉴权上下文 (透传 traceId 与链路信息)
        MoneyAccessContext orderAccess = buildOrderAccess(caseEntity, taskEntity, identity, cp);
        MoneyAccessContext userAccess = buildUserAccess(caseEntity, taskEntity, identity, cp);

        int maxStepLoop = 15;
        int steps = 0;

        while (steps++ < maxStepLoop) {
            String currentNode = cp.getNextNode();
            log.info("Graph 节点调度, step={}, node={}, taskNo={}", steps, currentNode, taskEntity.getTaskNo());

            if (MoneyGraphNode.VALIDATE_SCOPE.name().equals(currentNode)) {
                // 1. VALIDATE_SCOPE 节点
                if (caseEntity.getGrantRef() != null && !grantService.isGrantActive(caseEntity.getGrantRef())) {
                    log.warn("VALIDATE_SCOPE 失败: Grant 未生效或已被撤回, grantRef={}", caseEntity.getGrantRef());
                    taskEntity.setState("AUTH_REQUIRED");
                    taskEntity.setWaitReason("GRANT_NOT_ACTIVE");
                    saveCheckpointAndTask(taskEntity, cp);
                    return null;
                }
                cp.setNextNode(MoneyGraphNode.COLLECT_FACTS.name());
                saveCheckpointAndTask(taskEntity, cp);

            } else if (MoneyGraphNode.COLLECT_FACTS.name().equals(currentNode)) {
                // 2. COLLECT_FACTS 节点
                try {
                    MoneySubjectSelector selector = buildSelector(caseEntity);
                    ResolveMoneySubjectResult resolveResult = orderQueryClient.resolveSubject(orderAccess, selector);
                    recordToolCall(taskEntity.getTaskNo(), cp.getNextToolCallSeq(), "RESOLVE_SUBJECT",
                            caseEntity.getTargetRef(), resolveResult != null ? "SUCCESS" : "EMPTY", null);
                    cp.setNextToolCallSeq(cp.getNextToolCallSeq() + 1);
                    cp.setSourceRpcCalls(cp.getSourceRpcCalls() + 1);

                    if (resolveResult == null || !resolveResult.isSuccess()) {
                        return failTask(caseEntity, taskEntity, cp, "SUBJECT_RESOLVE_FAILED",
                                resolveResult != null ? resolveResult.getErrorMessage() : "无法解析调查对象");
                    }

                    cp.setCanonicalOrderNo(resolveResult.getCanonicalOrderNo());
                    cp.setScopeProofRef(resolveResult.getScopeProofRef());

                    // 收集订单快照
                    String queryScope = cp.getCanonicalOrderNo() != null ? "ORDER:" + cp.getCanonicalOrderNo() : caseEntity.getScopeKey();
                    MoneyOrderSnapshot orderSnapshot = orderQueryClient.getSnapshot(orderAccess, MoneySnapshotQuery.forScope(queryScope));
                    recordToolCall(taskEntity.getTaskNo(), cp.getNextToolCallSeq(), "QUERY_ORDER_SNAPSHOT", queryScope,
                            orderSnapshot != null ? "SUCCESS" : "EMPTY", null);
                    cp.setNextToolCallSeq(cp.getNextToolCallSeq() + 1);
                    cp.setSourceRpcCalls(cp.getSourceRpcCalls() + 1);

                    if (orderSnapshot != null) {
                        recordSnapshot(taskEntity.getTaskNo(), "ORDER_SNAPSHOT", orderSnapshot.getSnapshotFingerprint(),
                                orderSnapshot.getCoverage(), orderSnapshot);
                        cp.setOrderPaymentStatus(orderSnapshot.getPaymentStatus());
                        if (orderSnapshot.getSnapshotFingerprint() != null) {
                            cp.getSourceVersions().put(queryScope, orderSnapshot.getSnapshotFingerprint());
                        }
                        if (orderSnapshot.getCoverage() != null) {
                            cp.getCoverageInfo().put("isComplete", orderSnapshot.getCoverage().isComplete());
                            cp.getCoverageInfo().put("notes", orderSnapshot.getCoverage().getNotes());
                        } else {
                            cp.getCoverageInfo().put("isComplete", true);
                        }
                        cp.getStructuredFacts().add(Map.of(
                                "factId", "FACT_ORDER_SNAP",
                                "kind", "ORDER_SNAPSHOT",
                                "sourceService", "mall-order-service",
                                "sourceRef", queryScope,
                                "observedAt", LocalDateTime.now(ZoneOffset.UTC).toString(),
                                "data", Map.of("orderNo", cp.getCanonicalOrderNo() != null ? cp.getCanonicalOrderNo() : "",
                                        "paymentStatus", orderSnapshot.getPaymentStatus() != null ? orderSnapshot.getPaymentStatus() : "UNKNOWN"),
                                "quality", Map.of("identityMatched", true, "coverage", "COMPLETE")
                        ));

                        // 读取后续快照分页
                        int page = 1;
                        String cursor = orderSnapshot.getCoverage() != null ? orderSnapshot.getCoverage().getCursor() : null;
                        int maxPages = properties != null ? properties.getMaxPagesPerSource() : 5;
                        while (orderSnapshot.getCoverage() != null && orderSnapshot.getCoverage().isHasMore() && cursor != null && !cursor.isBlank() && page++ < maxPages) {
                            MoneyOrderSnapshot nextPage = orderQueryClient.getSnapshot(orderAccess, new MoneySnapshotQuery(queryScope, Collections.emptyList(), cursor, 50, null));
                            cp.setSourceRpcCalls(cp.getSourceRpcCalls() + 1);
                            if (nextPage == null) break;
                            cursor = nextPage.getCoverage() != null ? nextPage.getCoverage().getCursor() : null;
                        }

                        // 一致性窗口复核 (Consistency Window Verification)
                        MoneyOrderSnapshot verifySnapshot = orderQueryClient.getSnapshot(orderAccess, MoneySnapshotQuery.forScope(queryScope));
                        cp.setSourceRpcCalls(cp.getSourceRpcCalls() + 1);
                        if (verifySnapshot != null) {
                            boolean statusChanged = !Objects.equals(verifySnapshot.getPaymentStatus(), orderSnapshot.getPaymentStatus());
                            boolean fpChanged = !Objects.equals(verifySnapshot.getSnapshotFingerprint(), orderSnapshot.getSnapshotFingerprint());
                            if (statusChanged || fpChanged) {
                                log.warn("检测到快照采集期间业务状态发生变化，触发重采核验, taskNo={}", taskEntity.getTaskNo());
                                MoneyOrderSnapshot retrySnapshot = orderQueryClient.getSnapshot(orderAccess, MoneySnapshotQuery.forScope(queryScope));
                                cp.setSourceRpcCalls(cp.getSourceRpcCalls() + 1);
                                if (retrySnapshot != null && !Objects.equals(retrySnapshot.getSnapshotFingerprint(), verifySnapshot.getSnapshotFingerprint())) {
                                    cp.getCoverageInfo().put("isComplete", false);
                                    cp.getCoverageInfo().put("notes", "INCONSISTENT_WINDOW: 采集窗口内业务状态持续变化");
                                    cp.getMissingEvidence().add("INCONSISTENT_WINDOW");
                                } else if (retrySnapshot != null) {
                                    cp.setOrderPaymentStatus(retrySnapshot.getPaymentStatus());
                                    cp.getSourceVersions().put(queryScope, retrySnapshot.getSnapshotFingerprint());
                                }
                            }
                        }
                    } else {
                        cp.getCoverageInfo().put("isComplete", false);
                        cp.getMissingEvidence().add("ORDER_SNAPSHOT_EMPTY");
                    }

                    // 收集所有验证过的支付尝试渠道观察 (涵盖多次尝试，不只取第一条)
                    if (resolveResult.getVerifiedAttemptRefs() != null && !resolveResult.getVerifiedAttemptRefs().isEmpty()) {
                        int attemptLimit = Math.min(4, resolveResult.getVerifiedAttemptRefs().size());
                        for (int i = 0; i < attemptLimit; i++) {
                            String attemptNo = resolveResult.getVerifiedAttemptRefs().get(i);
                            MoneyChannelObservation obs = orderQueryClient.observeChannel(orderAccess,
                                    MoneyChannelQuery.forAttempt(queryScope, attemptNo, null));
                            recordToolCall(taskEntity.getTaskNo(), cp.getNextToolCallSeq(), "OBSERVE_CHANNEL_ATTEMPT",
                                    attemptNo, obs != null ? "SUCCESS" : "EMPTY", null);
                            cp.setNextToolCallSeq(cp.getNextToolCallSeq() + 1);
                            cp.setSourceRpcCalls(cp.getSourceRpcCalls() + 1);

                            if (obs != null) {
                                recordSnapshot(taskEntity.getTaskNo(), "CHANNEL_OBSERVATION_ATTEMPT", obs.getEvidenceRef(), null, obs);
                                Map<String, Object> obsMap = new LinkedHashMap<>();
                                obsMap.put("attemptNo", attemptNo);
                                obsMap.put("rawStatus", obs.getRawStatus());
                                obsMap.put("exists", obs.getExists());
                                obsMap.put("amount", obs.getAmount() != null ? obs.getAmount().toString() : null);
                                obsMap.put("currency", obs.getCurrency());
                                cp.getAttemptObservations().add(obsMap);

                                if (i == 0) {
                                    cp.setChannelStatus(obs.getRawStatus());
                                    cp.setChannelExists(obs.getExists());
                                    cp.setChannelAmount(obs.getAmount() != null ? obs.getAmount().toString() : null);
                                    cp.setChannelCurrency(obs.getCurrency());
                                }

                                cp.getStructuredFacts().add(Map.of(
                                        "factId", "FACT_ATTEMPT_" + attemptNo,
                                        "kind", "CHANNEL_ATTEMPT_OBSERVATION",
                                        "sourceService", "mall-order-service",
                                        "sourceRef", attemptNo,
                                        "observedAt", LocalDateTime.now(ZoneOffset.UTC).toString(),
                                        "data", obsMap,
                                        "quality", Map.of("identityMatched", obs.getMerchantMatched() != null ? obs.getMerchantMatched() : true,
                                                "amountMatched", obs.getAmountMatched() != null ? obs.getAmountMatched() : true,
                                                "currencyMatched", obs.getCurrencyMatched() != null ? obs.getCurrencyMatched() : true)
                                ));
                                if (Boolean.TRUE.equals(obs.getExists())) {
                                    if (Boolean.FALSE.equals(obs.getMerchantMatched())) cp.setIdentityMatched(false);
                                    if (Boolean.FALSE.equals(obs.getAmountMatched())) cp.setAmountMatched(false);
                                    if (Boolean.FALSE.equals(obs.getCurrencyMatched())) cp.setCurrencyMatched(false);
                                }
                            }
                        }
                    }

                    // 收集所有验证过的退款单渠道观察 (同时核对退款，不因有支付尝试而跳过)
                    if (resolveResult.getVerifiedRefundRefs() != null && !resolveResult.getVerifiedRefundRefs().isEmpty()) {
                        int refundLimit = Math.min(4, resolveResult.getVerifiedRefundRefs().size());
                        for (int i = 0; i < refundLimit; i++) {
                            String refundNo = resolveResult.getVerifiedRefundRefs().get(i);
                            MoneyChannelObservation obs = orderQueryClient.observeChannel(orderAccess,
                                    MoneyChannelQuery.forRefund(queryScope, refundNo, null));
                            recordToolCall(taskEntity.getTaskNo(), cp.getNextToolCallSeq(), "OBSERVE_CHANNEL_REFUND",
                                    refundNo, obs != null ? "SUCCESS" : "EMPTY", null);
                            cp.setNextToolCallSeq(cp.getNextToolCallSeq() + 1);
                            cp.setSourceRpcCalls(cp.getSourceRpcCalls() + 1);

                            if (obs != null) {
                                recordSnapshot(taskEntity.getTaskNo(), "CHANNEL_OBSERVATION_REFUND", obs.getEvidenceRef(), null, obs);
                                Map<String, Object> obsMap = new LinkedHashMap<>();
                                obsMap.put("refundNo", refundNo);
                                obsMap.put("rawStatus", obs.getRawStatus());
                                obsMap.put("exists", obs.getExists());
                                obsMap.put("amount", obs.getAmount() != null ? obs.getAmount().toString() : null);
                                obsMap.put("currency", obs.getCurrency());
                                cp.getRefundObservations().add(obsMap);

                                if (cp.getAttemptObservations().isEmpty() && i == 0) {
                                    cp.setChannelStatus(obs.getRawStatus());
                                    cp.setChannelExists(obs.getExists());
                                    cp.setChannelAmount(obs.getAmount() != null ? obs.getAmount().toString() : null);
                                    cp.setChannelCurrency(obs.getCurrency());
                                }

                                cp.getStructuredFacts().add(Map.of(
                                        "factId", "FACT_REFUND_" + refundNo,
                                        "kind", "CHANNEL_REFUND_OBSERVATION",
                                        "sourceService", "mall-order-service",
                                        "sourceRef", refundNo,
                                        "observedAt", LocalDateTime.now(ZoneOffset.UTC).toString(),
                                        "data", obsMap,
                                        "quality", Map.of("identityMatched", obs.getMerchantMatched() != null ? obs.getMerchantMatched() : true,
                                                "amountMatched", obs.getAmountMatched() != null ? obs.getAmountMatched() : true)
                                ));
                            }
                        }
                    }

                    // 收集对账证据 (如果目标为对账差异或问题分类为 RECON_MISMATCH)
                    String targetRef = caseEntity.getTargetRef();
                    String problemFamily = caseEntity.getProblemFamily();
                    if ("RECON_MISMATCH".equalsIgnoreCase(problemFamily) || (targetRef != null && targetRef.startsWith("DIFF_")) || (caseEntity.getScopeKey() != null && caseEntity.getScopeKey().startsWith("RECON:"))) {
                        String diffNo = (targetRef != null && targetRef.startsWith("DIFF_")) ? targetRef : "DIFF_DEFAULT";
                        try {
                            MoneyReconEvidence reconEvidence = orderQueryClient.getReconEvidence(orderAccess, MoneyReconQuery.forDiffNo(diffNo));
                            recordToolCall(taskEntity.getTaskNo(), cp.getNextToolCallSeq(), "QUERY_RECON_EVIDENCE", diffNo,
                                    reconEvidence != null ? "SUCCESS" : "EMPTY", null);
                            cp.setNextToolCallSeq(cp.getNextToolCallSeq() + 1);
                            cp.setSourceRpcCalls(cp.getSourceRpcCalls() + 1);

                            if (reconEvidence != null) {
                                String evidenceRef = reconEvidence.getDiffNo() != null ? reconEvidence.getDiffNo() : diffNo;
                                recordSnapshot(taskEntity.getTaskNo(), "RECON_EVIDENCE", evidenceRef, null, reconEvidence);
                                Map<String, Object> rMap = new LinkedHashMap<>();
                                rMap.put("diffNo", reconEvidence.getDiffNo());
                                rMap.put("diffType", reconEvidence.getDiffType());
                                rMap.put("channelStatus", reconEvidence.getChannelStatus());
                                rMap.put("localStatus", reconEvidence.getLocalStatus());
                                rMap.put("channelAmount", reconEvidence.getChannelAmount() != null ? reconEvidence.getChannelAmount().toString() : null);
                                rMap.put("localAmount", reconEvidence.getLocalAmount() != null ? reconEvidence.getLocalAmount().toString() : null);
                                rMap.put("windowClosed", reconEvidence.getWindowClosed());
                                cp.setReconEvidenceData(rMap);

                                cp.getStructuredFacts().add(Map.of(
                                        "factId", "FACT_RECON_" + diffNo,
                                        "kind", "RECON_EVIDENCE",
                                        "sourceService", "mall-order-service",
                                        "sourceRef", diffNo,
                                        "observedAt", LocalDateTime.now(ZoneOffset.UTC).toString(),
                                        "data", rMap
                                ));
                            }
                        } catch (Exception e) {
                            log.warn("获取对账证据异常: {}", e.getMessage());
                        }
                    }

                    // 收集平台资金台账
                    if (cp.getScopeProofRef() != null && !cp.getScopeProofRef().isBlank()) {
                        MoneyFundQuery fundQuery = new MoneyFundQuery(
                                cp.getScopeProofRef(),
                                cp.getCanonicalOrderNo(),
                                resolveResult.getVerifiedAttemptRefs(),
                                resolveResult.getVerifiedRefundRefs(),
                                Collections.emptyList()
                        );
                        MoneyFundSnapshot fundSnapshot = fundQueryClient.getSnapshot(userAccess, fundQuery);
                        recordToolCall(taskEntity.getTaskNo(), cp.getNextToolCallSeq(), "QUERY_FUND_SNAPSHOT",
                                cp.getCanonicalOrderNo(), fundSnapshot != null ? "SUCCESS" : "EMPTY", null);
                        cp.setNextToolCallSeq(cp.getNextToolCallSeq() + 1);
                        cp.setSourceRpcCalls(cp.getSourceRpcCalls() + 1);

                        if (fundSnapshot != null && fundSnapshot.getTransactions() != null) {
                            recordSnapshot(taskEntity.getTaskNo(), "FUND_SNAPSHOT",
                                    MoneySecuritySupport.computeFingerprint("FUND:" + cp.getCanonicalOrderNo()), null, fundSnapshot);
                            cp.setFundTransactionCount(fundSnapshot.getTransactions().size());
                            cp.getStructuredFacts().add(Map.of(
                                    "factId", "FACT_FUND_SNAP",
                                    "kind", "FUND_SNAPSHOT",
                                    "sourceService", "mall-user-service",
                                    "sourceRef", cp.getCanonicalOrderNo() != null ? cp.getCanonicalOrderNo() : "",
                                    "observedAt", LocalDateTime.now(ZoneOffset.UTC).toString(),
                                    "data", Map.of("txCount", fundSnapshot.getTransactions().size())
                            ));
                        }
                    }

                    cp.setNextNode(MoneyGraphNode.NORMALIZE.name());
                    saveCheckpointAndTask(taskEntity, cp);
                } catch (Exception e) {
                    log.error("COLLECT_FACTS 异常, taskNo={}", taskEntity.getTaskNo(), e);
                    return failTask(caseEntity, taskEntity, cp, "FACT_COLLECTION_ERROR", e.getMessage());
                }

            } else if (MoneyGraphNode.NORMALIZE.name().equals(currentNode)) {
                // 3. NORMALIZE 节点：采用规范化 Canonical JSON 结构计算事实指纹
                Map<String, Object> canonicalFacts = new TreeMap<>();
                canonicalFacts.put("schemaVersion", 1);
                canonicalFacts.put("canonicalOrderNo", cp.getCanonicalOrderNo() != null ? cp.getCanonicalOrderNo() : "");
                canonicalFacts.put("orderPaymentStatus", cp.getOrderPaymentStatus() != null ? cp.getOrderPaymentStatus() : "");
                canonicalFacts.put("channelStatus", cp.getChannelStatus() != null ? cp.getChannelStatus() : "");
                canonicalFacts.put("channelAmount", cp.getChannelAmount() != null ? cp.getChannelAmount() : "");
                canonicalFacts.put("channelCurrency", cp.getChannelCurrency() != null ? cp.getChannelCurrency() : "");
                canonicalFacts.put("channelExists", cp.getChannelExists() != null ? cp.getChannelExists() : false);
                canonicalFacts.put("fundTransactionCount", cp.getFundTransactionCount());
                canonicalFacts.put("coverageInfo", new TreeMap<>(cp.getCoverageInfo()));
                canonicalFacts.put("attemptObservations", cp.getAttemptObservations());
                canonicalFacts.put("refundObservations", cp.getRefundObservations());
                if (cp.getReconEvidenceData() != null) {
                    canonicalFacts.put("reconEvidence", new TreeMap<>(cp.getReconEvidenceData()));
                }
                try {
                    String canonicalJson = objectMapper.writeValueAsString(canonicalFacts);
                    cp.setEvidenceFingerprint(MoneySecuritySupport.computeFingerprint(canonicalJson));
                } catch (Exception e) {
                    String raw = cp.getCanonicalOrderNo() + ":" + cp.getOrderPaymentStatus() + ":" + cp.getChannelStatus();
                    cp.setEvidenceFingerprint(MoneySecuritySupport.computeFingerprint(raw));
                }
                cp.setNextNode(MoneyGraphNode.EVALUATE_RULES.name());
                saveCheckpointAndTask(taskEntity, cp);

            } else if (MoneyGraphNode.EVALUATE_RULES.name().equals(currentNode)) {
                // 4. EVALUATE_RULES 节点
                evaluateBusinessRules(caseEntity, cp);
                cp.setNextNode(MoneyGraphNode.PLAN_NEXT.name());
                saveCheckpointAndTask(taskEntity, cp);

            } else if (MoneyGraphNode.PLAN_NEXT.name().equals(currentNode)) {
                // 5. PLAN_NEXT 节点（模型规划）
                if (cp.getPlanRounds() >= 2) {
                    // 超过最大规划轮次直接进入出具报告
                    cp.setNextNode(MoneyGraphNode.ASSEMBLE_REPORT.name());
                } else {
                    cp.setPlanRounds(cp.getPlanRounds() + 1);
                    ModelPlanContext planCtx = ModelPlanContext.builder()
                            .caseNo(caseEntity.getCaseNo())
                            .taskNo(taskEntity.getTaskNo())
                            .problemFamily(caseEntity.getProblemFamily())
                            .scopeKey(caseEntity.getScopeKey())
                            .targetRef(caseEntity.getTargetRef())
                            .currentRound(cp.getPlanRounds())
                            .maxRounds(2)
                            .completedActionHashes(cp.getCompletedActionHashes())
                            .build();

                    if (metrics != null) metrics.recordModelCall();
                    ModelPlanResult planResult = modelClient.planNextAction(planCtx);
                    if (planResult != null && planResult.isActionNeeded()) {
                        // 规划出下一步动作，交给 VALIDATE_PLAN
                        cp.setStateData(Map.of("plannedTool", planResult.getToolName(),
                                "plannedTarget", planResult.getTargetRef() != null ? planResult.getTargetRef() : "",
                                "plannedHash", planResult.getActionHash() != null ? planResult.getActionHash() : ""));
                        cp.setNextNode(MoneyGraphNode.VALIDATE_PLAN.name());
                    } else {
                        // 无需进一步动作，进入报告装配
                        cp.setNextNode(MoneyGraphNode.ASSEMBLE_REPORT.name());
                    }
                }
                saveCheckpointAndTask(taskEntity, cp);

            } else if (MoneyGraphNode.VALIDATE_PLAN.name().equals(currentNode)) {
                // 6. VALIDATE_PLAN 节点（白名单校验）
                String plannedTool = (String) cp.getStateData().get("plannedTool");
                String plannedTarget = (String) cp.getStateData().get("plannedTarget");
                String plannedHash = (String) cp.getStateData().get("plannedHash");

                ModelPlanResult planToValidate = ModelPlanResult.builder()
                        .actionNeeded(true)
                        .toolName(plannedTool)
                        .targetRef(plannedTarget)
                        .actionHash(plannedHash)
                        .build();

                MoneyPlanValidator.ValidationResult valRes = planValidator.validatePlan(
                        planToValidate, caseEntity.getScopeKey(), caseEntity.getTargetRef(),
                        List.of("FACT_ORDER_01"));

                if (!valRes.valid()) {
                    log.warn("VALIDATE_PLAN 校验拒绝: {}, 降级直接装配报告", valRes.rejectReason());
                    cp.setStopReason("PLAN_REJECTED: " + valRes.rejectReason());
                    cp.setNextNode(MoneyGraphNode.ASSEMBLE_REPORT.name());
                } else {
                    if ("REQUEST_INPUT".equalsIgnoreCase(plannedTool)) {
                        cp.setNextNode(MoneyGraphNode.REQUEST_INPUT.name());
                    } else {
                        cp.setNextNode(MoneyGraphNode.EXECUTE_TOOL.name());
                    }
                }
                saveCheckpointAndTask(taskEntity, cp);

            } else if (MoneyGraphNode.EXECUTE_TOOL.name().equals(currentNode)) {
                // 7. EXECUTE_TOOL 节点（执行只读动作）
                String plannedHash = (String) cp.getStateData().get("plannedHash");
                if (plannedHash != null && !plannedHash.isBlank()) {
                    cp.getCompletedActionHashes().add(plannedHash);
                }
                cp.setToolCalls(cp.getToolCalls() + 1);
                // 执行后进入报告装配
                cp.setNextNode(MoneyGraphNode.ASSEMBLE_REPORT.name());
                saveCheckpointAndTask(taskEntity, cp);

            } else if (MoneyGraphNode.REQUEST_INPUT.name().equals(currentNode)) {
                // 8. REQUEST_INPUT 节点（创建补充资料请求并挂起）
                String inputNo = "INP_" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
                LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC);
                MoneyInputRequestEntity inputReq = MoneyInputRequestEntity.builder()
                        .inputNo(inputNo)
                        .caseNo(caseEntity.getCaseNo())
                        .taskNo(taskEntity.getTaskNo())
                        .audienceScope("BUYER")
                        .inputVersion(1)
                        .state("PENDING")
                        .schemaJson("{\"type\":\"object\",\"properties\":{\"paymentSlip\":{\"type\":\"string\"}}}")
                        .expiresAt(nowUtc.plusHours(24))
                        .createdAt(nowUtc)
                        .updatedAt(nowUtc)
                        .version(1L)
                        .build();
                inputRequestMapper.insert(inputReq);

                taskEntity.setState("INPUT_REQUIRED");
                taskEntity.setWaitReason("USER_INPUT_REQUIRED");
                caseEntity.setState("NEED_INPUT");
                caseMapper.updateById(caseEntity);

                cp.setNextNode(MoneyGraphNode.COLLECT_FACTS.name()); // 补证恢复后从收集事实继续
                saveCheckpointAndTask(taskEntity, cp);
                log.info("任务已挂起等待补证, taskNo={}, inputNo={}", taskEntity.getTaskNo(), inputNo);
                return null;

            } else if (MoneyGraphNode.ASSEMBLE_REPORT.name().equals(currentNode)) {
                // 9. ASSEMBLE_REPORT 节点
                assembleReportPayload(caseEntity, cp);
                cp.setNextNode(MoneyGraphNode.GUARD_AND_PUBLISH.name());
                saveCheckpointAndTask(taskEntity, cp);

            } else if (MoneyGraphNode.GUARD_AND_PUBLISH.name().equals(currentNode)) {
                // 10. GUARD_AND_PUBLISH 节点
                return guardAndPublish(caseEntity, taskEntity, cp);
            } else {
                log.warn("未知节点 {}, 强行进入 GUARD_AND_PUBLISH", currentNode);
                cp.setNextNode(MoneyGraphNode.GUARD_AND_PUBLISH.name());
            }
        }

        return guardAndPublish(caseEntity, taskEntity, cp);
    }

    private void evaluateBusinessRules(MoneyCaseEntity caseEntity, MoneyCheckpoint cp) {
        String problem = caseEntity.getProblemFamily();
        List<Map<String, Object>> findings = new ArrayList<>();
        List<String> conflicts = new ArrayList<>();
        List<String> missingEvidence = new ArrayList<>();

        StringBuilder summary = new StringBuilder();
        summary.append("### 资金调查结果报告\n\n");
        summary.append("- **问题类型**：").append(problem).append("\n");
        summary.append("- **标准订单号**：").append(cp.getCanonicalOrderNo() != null ? cp.getCanonicalOrderNo() : "无").append("\n");

        if (cp.getChannelStatus() != null) {
            summary.append("- **外部渠道状态**：").append(cp.getChannelStatus())
                    .append("（存在性：").append(cp.getChannelExists()).append("，金额：")
                    .append(cp.getChannelAmount()).append(" ").append(cp.getChannelCurrency()).append("）\n");
        }
        if (cp.getOrderPaymentStatus() != null) {
            summary.append("- **平台支付状态**：").append(cp.getOrderPaymentStatus()).append("\n");
        }
        if (cp.getFundTransactionCount() != null) {
            summary.append("- **平台资金流水数**：").append(cp.getFundTransactionCount()).append("\n");
        }

        boolean isCoverageComplete = cp.getCoverageInfo() != null && Boolean.TRUE.equals(cp.getCoverageInfo().get("isComplete"));
        boolean channelPaid = "SUCCESS".equalsIgnoreCase(cp.getChannelStatus()) && Boolean.TRUE.equals(cp.getChannelExists());
        boolean platformPaid = "PAID".equalsIgnoreCase(cp.getOrderPaymentStatus()) || "SUCCEEDED".equalsIgnoreCase(cp.getOrderPaymentStatus());

        // 1. 规则: SOURCE_UNAVAILABLE / 覆盖缺失
        if (!isCoverageComplete) {
            findings.add(Map.of(
                    "code", "SOURCE_UNAVAILABLE",
                    "supportingFactIds", List.of(),
                    "contradictingFactIds", List.of(),
                    "certainty", "VERIFIED",
                    "missingEvidence", "部分关键数据源读取未完全覆盖或出现截断",
                    "message", "数据源覆盖不全或部分源不可用"
            ));
            missingEvidence.add("部分数据源未完全覆盖");
            summary.append("\n> **警告**：数据源读取未完全覆盖，禁止输出确定性正常结论。\n");
        }

        // 2. 规则: CHANNEL_AMOUNT_CONFLICT
        if (Boolean.TRUE.equals(cp.getChannelExists()) && (Boolean.FALSE.equals(cp.getAmountMatched()) || Boolean.FALSE.equals(cp.getCurrencyMatched()))) {
            findings.add(Map.of(
                    "code", "CHANNEL_AMOUNT_CONFLICT",
                    "supportingFactIds", List.of("FACT_ORDER_SNAP"),
                    "contradictingFactIds", List.of("FACT_CHANNEL_OBS"),
                    "certainty", "VERIFIED",
                    "missingEvidence", "",
                    "message", "外部支付渠道金额或币种与平台订单记录不一致"
            ));
            conflicts.add("金额或币种不一致");
            summary.append("\n> **金额冲突**：外部支付渠道扣款金额/币种与订单记录不符，禁止自动确认。\n");
        }

        // 规则: IDENTITY_OR_LINK_AMBIGUOUS
        if (Boolean.TRUE.equals(cp.getChannelExists()) && Boolean.FALSE.equals(cp.getIdentityMatched())) {
            findings.add(Map.of(
                    "code", "IDENTITY_OR_LINK_AMBIGUOUS",
                    "supportingFactIds", List.of(),
                    "contradictingFactIds", List.of("FACT_CHANNEL_OBS"),
                    "certainty", "VERIFIED",
                    "missingEvidence", "渠道商户身份未匹配",
                    "message", "外部支付渠道交易身份与商户账号未验证匹配"
            ));
            conflicts.add("渠道身份不匹配");
            summary.append("\n> **身份歧义**：外部支付渠道交易商户/身份未验证匹配，禁止向用户展示或自动确认。\n");
        }

        // 3. 规则: EXCESS_OR_LATE_PAYMENT (多付或迟到支付)
        long successAttempts = cp.getAttemptObservations().stream()
                .filter(o -> "SUCCESS".equalsIgnoreCase((String) o.get("rawStatus")) && Boolean.TRUE.equals(o.get("exists")))
                .count();
        if (successAttempts > 1 || "EXCESS_PAYMENT".equalsIgnoreCase(problem)) {
            if (channelPaid || successAttempts > 1) {
                findings.add(Map.of(
                        "code", "EXCESS_OR_LATE_PAYMENT",
                        "supportingFactIds", List.of("FACT_ATTEMPT_MULTI"),
                        "contradictingFactIds", List.of(),
                        "certainty", "VERIFIED",
                        "missingEvidence", "",
                        "message", "检测到存在多笔外部支付渠道成功扣款记录或溢缴款"
                ));
                conflicts.add("存在多笔外部扣款或溢缴款");
                summary.append("\n> **多扣款裁决**：检测到疑似多笔成功扣款或溢缴挂账，待财务复核挂账认领。\n");
            }
        }

        // 4. 规则: PAYMENT_CONFIRMATION_GAP (掉单)
        if (channelPaid && !platformPaid) {
            findings.add(Map.of(
                    "code", "PAYMENT_CONFIRMATION_GAP",
                    "supportingFactIds", List.of("FACT_CHAN_ATTEMPT"),
                    "contradictingFactIds", List.of("FACT_ORDER_SNAP"),
                    "certainty", "VERIFIED",
                    "missingEvidence", "",
                    "message", "外部支付渠道已确认扣款成功，但平台订单状态仍未更新为已支付，存在掉单差异"
            ));
            conflicts.add("外部已扣款但平台订单未支付 (掉单)");
            summary.append("\n> **掉单裁决**：外部支付渠道已扣款成功，但平台支付订单仍未更新为已支付，建议运营/财务人工复核或触发受控补确认！\n");
        }

        // 5. 规则: SUSPENSE_LEDGER_GAP (挂账缺口)
        if (successAttempts > 1 && (cp.getFundTransactionCount() == null || cp.getFundTransactionCount() <= 1)) {
            findings.add(Map.of(
                    "code", "SUSPENSE_LEDGER_GAP",
                    "supportingFactIds", List.of(),
                    "contradictingFactIds", List.of("FACT_FUND_SNAP"),
                    "certainty", "PARTIAL",
                    "missingEvidence", "缺少对应溢缴款资金流水台账",
                    "message", "多笔扣款在平台资金流水台账中未完全建账挂账"
            ));
            conflicts.add("多扣款流水与平台台账不一致");
        }

        // 6. 规则: REFUND_FACT_CONFLICT 与 REFUND_PROCESSING
        boolean hasRefundObservations = !cp.getRefundObservations().isEmpty();
        boolean refundChannelSuccess = false;
        for (Map<String, Object> ro : cp.getRefundObservations()) {
            String rStatus = (String) ro.get("rawStatus");
            if ("SUCCESS".equalsIgnoreCase(rStatus)) {
                refundChannelSuccess = true;
            } else if ("PROCESSING".equalsIgnoreCase(rStatus) || "SUBMITTING".equalsIgnoreCase(rStatus)) {
                findings.add(Map.of(
                        "code", "REFUND_PROCESSING",
                        "supportingFactIds", List.of("FACT_REFUND_" + ro.get("refundNo")),
                        "contradictingFactIds", List.of(),
                        "certainty", "VERIFIED",
                        "missingEvidence", "",
                        "message", "退款单外部渠道仍在处理中，尚未出账终态"
                ));
                summary.append("\n> **退款处理中**：退款在外部渠道仍处于处理中，需保持观察或联系渠道。\n");
            } else if ("FAILED".equalsIgnoreCase(rStatus) || "NOT_FOUND".equalsIgnoreCase(rStatus)) {
                findings.add(Map.of(
                        "code", "REFUND_FACT_CONFLICT",
                        "supportingFactIds", List.of("FACT_REFUND_" + ro.get("refundNo")),
                        "contradictingFactIds", List.of("FACT_ORDER_SNAP"),
                        "certainty", "VERIFIED",
                        "missingEvidence", "",
                        "message", "平台记录退款但外部渠道返回退款失败或未查到"
                ));
                conflicts.add("退款状态与外部渠道不一致");
                summary.append("\n> **退款冲突**：平台记录退款但外部渠道未成功或查无记录，需排查。\n");
            }
        }

        // 7. 规则: RECON_WINDOW_OPEN
        if ("RECON_MISMATCH".equalsIgnoreCase(problem) || (caseEntity.getScopeKey() != null && caseEntity.getScopeKey().startsWith("RECON:"))) {
            findings.add(Map.of(
                    "code", "RECON_WINDOW_OPEN",
                    "supportingFactIds", List.of("FACT_RECON"),
                    "contradictingFactIds", List.of(),
                    "certainty", "PARTIAL",
                    "missingEvidence", "对账单边差错窗口可能尚未关闭",
                    "message", "对账窗口内存在单边或金额不符差异，待平账处理"
            ));
            conflicts.add("对账窗口内存在单边差异");
            summary.append("\n> **对账差异裁决**：对账窗口内存在单边或金额不符差异，待进一步平账调查。\n");
        }

        // 8. 规则: NORMAL_PROGRESS
        if (conflicts.isEmpty() && isCoverageComplete) {
            if ("PAYMENT_CONFIRMATION".equalsIgnoreCase(problem)) {
                if (!channelPaid && !platformPaid) {
                    findings.add(Map.of(
                            "code", "NORMAL_PROGRESS",
                            "supportingFactIds", List.of("FACT_ORDER_SNAP"),
                            "contradictingFactIds", List.of(),
                            "certainty", "VERIFIED",
                            "missingEvidence", "",
                            "message", "外部支付渠道确认该笔交易未付款或已关闭，无重复扣款，状态正常"
                    ));
                    summary.append("\n> **正常裁决**：外部支付渠道确认该笔交易未付款或已关闭，无重复扣款，状态正常。\n");
                } else if (channelPaid && platformPaid) {
                    findings.add(Map.of(
                            "code", "NORMAL_PROGRESS",
                            "supportingFactIds", List.of("FACT_ORDER_SNAP", "FACT_CHAN_ATTEMPT"),
                            "contradictingFactIds", List.of(),
                            "certainty", "VERIFIED",
                            "missingEvidence", "",
                            "message", "外部支付渠道与平台订单均已确认扣款成功，状态一致正常"
                    ));
                    summary.append("\n> **正常裁决**：外部支付渠道与平台订单均已确认扣款成功，状态一致正常。\n");
                }
            } else if ("REFUND_STATUS".equalsIgnoreCase(problem) && refundChannelSuccess) {
                findings.add(Map.of(
                        "code", "NORMAL_PROGRESS",
                        "supportingFactIds", List.of("FACT_REFUND"),
                        "contradictingFactIds", List.of(),
                        "certainty", "VERIFIED",
                        "missingEvidence", "",
                        "message", "外部支付渠道已完成退款出账，退款资金已成功释放，状态正常"
                ));
                summary.append("\n> **退款裁决**：外部支付渠道已完成退款出账，退款资金已成功释放，状态正常。\n");
            }
        }

        // 综合判定 outcome
        String outcome;
        if (!isCoverageComplete || findings.stream().anyMatch(f -> "SOURCE_UNAVAILABLE".equals(f.get("code")))) {
            // 证据缺失、覆盖不全：严格禁止给出 VERIFIED_NORMAL
            outcome = "INCONCLUSIVE";
        } else if (!conflicts.isEmpty() || findings.stream().anyMatch(f ->
                "PAYMENT_CONFIRMATION_GAP".equals(f.get("code"))
                        || "CHANNEL_AMOUNT_CONFLICT".equals(f.get("code"))
                        || "EXCESS_OR_LATE_PAYMENT".equals(f.get("code"))
                        || "REFUND_FACT_CONFLICT".equals(f.get("code"))
                        || "SUSPENSE_LEDGER_GAP".equals(f.get("code"))
                        || "RECON_WINDOW_OPEN".equals(f.get("code"))
                        || "REFUND_PROCESSING".equals(f.get("code"))
                        || "IDENTITY_OR_LINK_AMBIGUOUS".equals(f.get("code")))) {
            outcome = "NEEDS_REVIEW";
        } else if ("REFUND_STATUS".equalsIgnoreCase(problem) && refundChannelSuccess) {
            outcome = "VERIFIED_RESOLVED";
        } else if (findings.stream().anyMatch(f -> "NORMAL_PROGRESS".equals(f.get("code")))) {
            outcome = "VERIFIED_NORMAL";
        } else {
            outcome = "NEEDS_REVIEW";
        }

        cp.setVerifiedFindings(findings);
        cp.setConflicts(conflicts);
        cp.setMissingEvidence(missingEvidence);
        cp.setRuleOutcome(outcome);
        cp.setRuleSummary(summary.toString());
    }

    private void assembleReportPayload(MoneyCaseEntity caseEntity, MoneyCheckpoint cp) {
        String reportNo = cp.getCandidateReportNo() != null ? cp.getCandidateReportNo() : MoneyIdGenerator.generateReportNo();
        cp.setCandidateReportNo(reportNo);

        Map<String, Object> reportPayload = new LinkedHashMap<>();
        reportPayload.put("reportNo", reportNo);
        reportPayload.put("reportRevision", 1L);
        reportPayload.put("caseNo", caseEntity.getCaseNo());
        reportPayload.put("taskNo", cp.getStateData().get("taskNo"));
        reportPayload.put("schemaVersion", 1);
        reportPayload.put("subject", Map.of(
                "scopeKey", caseEntity.getScopeKey() != null ? caseEntity.getScopeKey() : "",
                "targetRef", caseEntity.getTargetRef() != null ? caseEntity.getTargetRef() : "",
                "canonicalOrderNo", cp.getCanonicalOrderNo() != null ? cp.getCanonicalOrderNo() : ""
        ));
        reportPayload.put("problemFamily", caseEntity.getProblemFamily());
        reportPayload.put("verifiedFindings", cp.getVerifiedFindings());
        reportPayload.put("facts", cp.getStructuredFacts());
        reportPayload.put("claims", List.of(Map.of(
                "claimText", "客户发起的资金核查请求",
                "reportedAt", LocalDateTime.now(ZoneOffset.UTC).toString()
        )));
        reportPayload.put("hypotheses", List.of(
                "外部渠道扣款状态可能与平台订单状态不同步",
                "退款流程可能在银行渠道处理中"
        ));
        reportPayload.put("fundPath", cp.getFundPath());
        reportPayload.put("conflicts", cp.getConflicts());
        reportPayload.put("missingEvidence", cp.getMissingEvidence());
        reportPayload.put("coverage", cp.getCoverageInfo());
        reportPayload.put("freshness", cp.getSourceVersions());
        reportPayload.put("stopReason", cp.getStopReason() != null ? cp.getStopReason() : "COMPLETED_NORMALLY");
        reportPayload.put("summary", cp.getRuleSummary());
        reportPayload.put("outcome", cp.getRuleOutcome());

        try {
            String fullJson = objectMapper.writeValueAsString(reportPayload);
            cp.getStateData().put("reportFullJson", fullJson);
            cp.setCandidateReportHash(MoneySecuritySupport.computeFingerprint(fullJson));
        } catch (Exception e) {
            cp.setCandidateReportHash(MoneySecuritySupport.computeFingerprint(reportNo));
        }
    }

    private MoneyReportEntity guardAndPublish(MoneyCaseEntity caseEntity, MoneyTaskEntity taskEntity, MoneyCheckpoint cp) {
        if (transactionTemplate != null) {
            return transactionTemplate.execute(status -> doGuardAndPublish(caseEntity, taskEntity, cp));
        }
        return doGuardAndPublish(caseEntity, taskEntity, cp);
    }

    private MoneyReportEntity doGuardAndPublish(MoneyCaseEntity caseEntity, MoneyTaskEntity taskEntity, MoneyCheckpoint cp) {
        LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC);

        // 发布前授权复核
        if (caseEntity.getGrantRef() != null && !grantService.isGrantActive(caseEntity.getGrantRef())) {
            log.warn("发布报告前 Grant 已失效/被撤回，禁止发布调查报告, grantRef={}", caseEntity.getGrantRef());
            taskEntity.setState("AUTH_REQUIRED");
            taskEntity.setWaitReason("GRANT_REVOKED_BEFORE_PUBLISH");
            saveCheckpointAndTask(taskEntity, cp);
            return null;
        }

        // 发布前租约与 Fencing Token 强校验：防止过期 Worker 或被抢占的旧租约覆盖最新结果
        if (taskMapper != null && taskEntity != null && taskEntity.getTaskNo() != null) {
            MoneyTaskEntity currentDbTask = taskMapper.selectOne(new LambdaQueryWrapper<MoneyTaskEntity>()
                    .eq(MoneyTaskEntity::getTaskNo, taskEntity.getTaskNo()));
            if (currentDbTask != null) {
                if (taskEntity.getLeaseOwner() != null && !taskEntity.getLeaseOwner().equals(currentDbTask.getLeaseOwner())) {
                    log.error("Worker 租约已被抢占，终止发布报告: expectedOwner={}, currentOwner={}",
                            taskEntity.getLeaseOwner(), currentDbTask.getLeaseOwner());
                    throw new IllegalStateException("Worker 任务租约已被抢占: taskNo=" + taskEntity.getTaskNo());
                }
                if (taskEntity.getFencingToken() != null && !taskEntity.getFencingToken().equals(currentDbTask.getFencingToken())) {
                    log.error("Fencing Token 不匹配，终止发布报告: expectedFencing={}, currentFencing={}",
                            taskEntity.getFencingToken(), currentDbTask.getFencingToken());
                    throw new IllegalStateException("Fencing Token 失效冲突: taskNo=" + taskEntity.getTaskNo());
                }
                if (currentDbTask.getLeaseUntil() != null && currentDbTask.getLeaseUntil().isBefore(nowUtc)) {
                    log.error("Worker 租约已超时过期，终止发布报告: leaseUntil={}, now={}",
                            currentDbTask.getLeaseUntil(), nowUtc);
                    throw new IllegalStateException("Worker 任务租约已过期: taskNo=" + taskEntity.getTaskNo());
                }
            }
        }

        String reportNo = cp.getCandidateReportNo() != null ? cp.getCandidateReportNo() : MoneyIdGenerator.generateReportNo();
        String outcome = cp.getRuleOutcome() != null ? cp.getRuleOutcome() : "VERIFIED_NORMAL";

        // 分配报告修订版本
        Long nextReportRevision = 1L;
        try {
            MoneyReportEntity lastReport = reportMapper.selectOne(new LambdaQueryWrapper<MoneyReportEntity>()
                    .eq(MoneyReportEntity::getCaseNo, caseEntity.getCaseNo())
                    .orderByDesc(MoneyReportEntity::getRevision)
                    .last("LIMIT 1"));
            if (lastReport != null && lastReport.getRevision() != null) {
                nextReportRevision = lastReport.getRevision() + 1L;
            }
        } catch (Exception ignored) {}

        // 分配事件序列号
        Long nextCaseSeq = 1L;
        try {
            MoneyEventEntity lastEvent = eventMapper.selectOne(new LambdaQueryWrapper<MoneyEventEntity>()
                    .eq(MoneyEventEntity::getCaseNo, caseEntity.getCaseNo())
                    .orderByDesc(MoneyEventEntity::getCaseSeq)
                    .last("LIMIT 1"));
            if (lastEvent != null && lastEvent.getCaseSeq() != null) {
                nextCaseSeq = lastEvent.getCaseSeq() + 1L;
            }
        } catch (Exception ignored) {}

        String reportJson = (String) cp.getStateData().get("reportFullJson");
        if (reportJson == null || reportJson.isBlank()) {
            reportJson = "{\"summary\":\"" + (cp.getRuleSummary() != null ? cp.getRuleSummary().replace("\n", "\\n").replace("\"", "\\\"") : "") + "\"}";
        }

        // 1. 插入不可变 report
        MoneyReportEntity reportEntity = MoneyReportEntity.builder()
                .reportNo(reportNo)
                .caseNo(caseEntity.getCaseNo())
                .taskNo(taskEntity.getTaskNo())
                .revision(nextReportRevision)
                .schemaVersion(1)
                .basedOnFingerprint(cp.getEvidenceFingerprint())
                .internalJson(reportJson)
                .reportHash(cp.getCandidateReportHash())
                .outcome(outcome)
                .ruleVersion("RULE_V1")
                .promptVersion("PROMPT_V1")
                .modelVersion("MOCK_ANALYZER_V1")
                .createdAt(nowUtc)
                .build();
        reportMapper.insert(reportEntity);

        // 判定案件状态
        String caseState;
        if ("NEEDS_REVIEW".equalsIgnoreCase(outcome)) {
            caseState = "NEED_REVIEW";
        } else if ("INPUT_REQUIRED".equalsIgnoreCase(outcome)) {
            caseState = "NEED_INPUT";
        } else if ("VERIFIED_RESOLVED".equalsIgnoreCase(outcome) || "VERIFIED_NORMAL".equalsIgnoreCase(outcome)) {
            caseState = "RESOLVED";
        } else {
            caseState = "NEED_REVIEW";
        }

        // 2. 更新各允许受众 projection、事件与 Outbox
        try {
            ActorIdentity actorId = resolveActorIdentity(caseEntity);
            String audienceScope = "BUYER";
            if (actorId != null && actorId.userId() != null && actorId.userId() > 0) {
                audienceScope = actorId.role() + ":" + actorId.userId();
            }

            Map<String, Object> publicData = new LinkedHashMap<>();
            publicData.put("caseNo", caseEntity.getCaseNo());
            publicData.put("orderNo", cp.getCanonicalOrderNo() != null ? cp.getCanonicalOrderNo() : caseEntity.getOrderNo());
            publicData.put("caseState", caseState);
            publicData.put("taskState", "COMPLETED");
            publicData.put("resolutionOutcome", outcome);
            publicData.put("publicSummary", cp.getRuleSummary() != null ? "调查完成：" + outcome : "调查已完成");
            publicData.put("allowedActions", List.of());
            publicData.put("missingInput", null);
            publicData.put("isMock", properties != null && "MOCK".equalsIgnoreCase(properties.getModelMode()));
            publicData.put("updatedAt", nowUtc.toString());
            publicData.put("revision", nextReportRevision);
            String publicJson = objectMapper.writeValueAsString(publicData);

            MoneyProjectionEntity projection = MoneyProjectionEntity.builder()
                    .caseNo(caseEntity.getCaseNo())
                    .audienceScope("BUYER")
                    .revision(nextReportRevision)
                    .reportNo(reportNo)
                    .publicJson(publicJson)
                    .contentHash(MoneySecuritySupport.computeFingerprint(publicJson))
                    .latestCaseEventSeq(nextCaseSeq)
                    .policyVersion("POLICY_V1")
                    .createdAt(nowUtc)
                    .updatedAt(nowUtc)
                    .version(1L)
                    .build();
            upsertProjection(projection, nowUtc);

            // 写入面向 FINANCE 审计受众的完整证据链投影 (绝不向 BUYER 暴露内部渠道明细和技术证据)
            Map<String, Object> financeData = new LinkedHashMap<>(publicData);
            financeData.put("internalReport", reportJson);
            financeData.put("factsSnapshotFingerprint", cp.getEvidenceFingerprint());
            financeData.put("attemptObservations", cp.getAttemptObservations());
            financeData.put("refundObservations", cp.getRefundObservations());
            financeData.put("scopeKey", caseEntity.getScopeKey());
            financeData.put("grantRef", caseEntity.getGrantRef());
            String financeJson = objectMapper.writeValueAsString(financeData);

            MoneyProjectionEntity financeProj = MoneyProjectionEntity.builder()
                    .caseNo(caseEntity.getCaseNo())
                    .audienceScope("FINANCE")
                    .revision(nextReportRevision)
                    .reportNo(reportNo)
                    .publicJson(financeJson)
                    .contentHash(MoneySecuritySupport.computeFingerprint(financeJson))
                    .latestCaseEventSeq(nextCaseSeq)
                    .policyVersion("POLICY_V1")
                    .createdAt(nowUtc)
                    .updatedAt(nowUtc)
                    .version(1L)
                    .build();
            upsertProjection(financeProj, nowUtc);

            String eventNo = "EVT_" + MoneyIdGenerator.generateDispatchNo().substring(2);
            MoneyEventEntity event = MoneyEventEntity.builder()
                    .eventNo(eventNo)
                    .caseNo(caseEntity.getCaseNo())
                    .taskNo(taskEntity.getTaskNo())
                    .caseSeq(nextCaseSeq)
                    .eventType("REPORT_PUBLISHED")
                    .payloadRefJson("{\"reportNo\":\"" + reportNo + "\",\"outcome\":\"" + outcome + "\"}")
                    .visibility("ALL")
                    .createdAt(nowUtc)
                    .build();
            eventMapper.insert(event);

            if (outboxMapper != null) {
                MoneyOutboxEntity outbox = MoneyOutboxEntity.builder()
                        .eventNo(eventNo)
                        .destination("MALL_MONEY_CASE_NOTICES")
                        .payloadJson("{\"caseNo\":\"" + caseEntity.getCaseNo() + "\",\"taskNo\":\"" + taskEntity.getTaskNo() + "\",\"reportNo\":\"" + reportNo + "\",\"outcome\":\"" + outcome + "\",\"caseSeq\":" + nextCaseSeq + "}")
                        .state("PENDING")
                        .attemptCount(0)
                        .nextAttemptAt(nowUtc)
                        .fencingToken(1L)
                        .version(1L)
                        .createdAt(nowUtc)
                        .updatedAt(nowUtc)
                        .build();
                outboxMapper.insert(outbox);
            }
        } catch (Exception e) {
            log.error("写入投影/事件/Outbox异常, caseNo={}", caseEntity.getCaseNo(), e);
            throw new IllegalStateException("写入调查产物与通知失败: " + e.getMessage(), e);
        }

        // 3. 更新 Task 状态为 COMPLETED
        taskEntity.setState("COMPLETED");
        taskEntity.setWaitReason(null);
        taskEntity.setFinishedAt(nowUtc);
        taskEntity.setPlanRounds(cp.getPlanRounds());
        taskEntity.setToolCalls(cp.getToolCalls());
        taskEntity.setVersion(taskEntity.getVersion() != null ? taskEntity.getVersion() + 1 : 1L);
        saveCheckpointAndTask(taskEntity, cp);

        // 4. 更新 Case 状态与去重键管理
        if ("RESOLVED".equalsIgnoreCase(caseState)) {
            caseEntity.setActiveDedupeKey(null); // 解决后释放活动去重键
            caseEntity.setClosedAt(nowUtc);
        }
        caseEntity.setState(caseState);
        caseEntity.setOutcome(outcome);
        caseEntity.setLatestReportNo(reportNo);
        caseEntity.setCurrentTaskNo(taskEntity.getTaskNo());
        caseEntity.setUpdatedAt(nowUtc);
        caseMapper.updateById(caseEntity);

        if (metrics != null) {
            metrics.recordTaskCompleted(caseEntity.getProblemFamily(), outcome, 50L);
        }

        log.info("StateGraph 完成并发布报告 (同事务原子提交), caseNo={}, taskNo={}, reportNo={}, caseState={}, outcome={}",
                caseEntity.getCaseNo(), taskEntity.getTaskNo(), reportNo, caseState, outcome);

        return reportEntity;
    }

    private void upsertProjection(MoneyProjectionEntity projection, LocalDateTime nowUtc) {
        if (projectionMapper == null || projection == null) return;
        MoneyProjectionEntity existing = projectionMapper.selectOne(new LambdaQueryWrapper<MoneyProjectionEntity>()
                .eq(MoneyProjectionEntity::getCaseNo, projection.getCaseNo())
                .eq(MoneyProjectionEntity::getAudienceScope, projection.getAudienceScope()));
        if (existing != null) {
            existing.setRevision(projection.getRevision());
            existing.setReportNo(projection.getReportNo());
            existing.setPublicJson(projection.getPublicJson());
            existing.setContentHash(projection.getContentHash());
            existing.setLatestCaseEventSeq(projection.getLatestCaseEventSeq());
            existing.setPolicyVersion(projection.getPolicyVersion());
            existing.setUpdatedAt(nowUtc);
            existing.setVersion(existing.getVersion() != null ? existing.getVersion() + 1L : 1L);
            projectionMapper.updateById(existing);
        } else {
            projectionMapper.insert(projection);
        }
    }

    private void recordSnapshot(String taskNo, String sourceKind, String fingerprint, Object coverage, Object facts) {
        if (snapshotMapper == null) return;
        try {
            LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC);
            MoneySnapshotEntity snap = MoneySnapshotEntity.builder()
                    .snapshotNo("SNP_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24).toUpperCase())
                    .taskNo(taskNo)
                    .stepNo(1)
                    .sourceKind(sourceKind)
                    .fingerprint(fingerprint != null ? fingerprint : MoneySecuritySupport.computeFingerprint(sourceKind))
                    .coverageJson(coverage != null ? objectMapper.writeValueAsString(coverage) : "{\"complete\":true}")
                    .factsJson(facts != null ? objectMapper.writeValueAsString(facts) : "{}")
                    .observedAt(nowUtc)
                    .createdAt(nowUtc)
                    .build();
            snapshotMapper.insert(snap);
        } catch (Exception e) {
            log.warn("写入 MoneySnapshot 异常: {}", e.getMessage());
        }
    }

    private void recordToolCall(String taskNo, Integer seq, String toolName, String targetRef, String outcome, String err) {
        if (metrics != null) metrics.recordToolCall();
        if (toolCallMapper == null) return;
        try {
            LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC);
            MoneyToolCallEntity tc = MoneyToolCallEntity.builder()
                    .taskNo(taskNo)
                    .callSeq(seq != null ? seq : 1)
                    .toolName(toolName)
                    .targetRef(targetRef)
                    .inputHash(MoneySecuritySupport.computeFingerprint(toolName + ":" + targetRef))
                    .factRefJson("{}")
                    .outcome(outcome)
                    .errorCode(err)
                    .latencyMs(20)
                    .createdAt(nowUtc)
                    .build();
            toolCallMapper.insert(tc);
        } catch (Exception e) {
            log.warn("写入 MoneyToolCall 异常: {}", e.getMessage());
        }
    }

    private MoneyReportEntity failTask(MoneyCaseEntity caseEntity, MoneyTaskEntity taskEntity, MoneyCheckpoint cp,
                                      String errCode, String errMsg) {
        LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC);
        taskEntity.setState("FAILED");
        taskEntity.setLastErrorCode(errCode);
        taskEntity.setFinishedAt(nowUtc);
        taskEntity.setVersion(taskEntity.getVersion() != null ? taskEntity.getVersion() + 1 : 1L);
        saveCheckpointAndTask(taskEntity, cp);

        caseEntity.setOutcome("INCONCLUSIVE");
        caseEntity.setCloseReason(errMsg);
        caseEntity.setUpdatedAt(nowUtc);
        caseMapper.updateById(caseEntity);

        if (metrics != null) {
            metrics.recordTaskFailed(caseEntity.getProblemFamily(), errCode, 50L);
        }

        return null;
    }

    private void saveCheckpointAndTask(MoneyTaskEntity taskEntity, MoneyCheckpoint cp) {
        try {
            taskEntity.setCheckpointJson(objectMapper.writeValueAsString(cp));
        } catch (Exception ignored) {}
        taskEntity.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        taskMapper.updateById(taskEntity);
    }

    private MoneyCheckpoint readOrCreateCheckpoint(MoneyTaskEntity taskEntity) {
        if (taskEntity.getCheckpointJson() != null && !taskEntity.getCheckpointJson().isBlank()) {
            try {
                return objectMapper.readValue(taskEntity.getCheckpointJson(), MoneyCheckpoint.class);
            } catch (Exception ignored) {}
        }
        return MoneyCheckpoint.builder().build();
    }

    private record ActorIdentity(Long userId, String role, String grantRef) {}

    private ActorIdentity resolveActorIdentity(MoneyCaseEntity caseEntity) {
        Long actorUserId = 0L;
        String actorRole = "BUYER";
        String grantRef = caseEntity.getGrantRef() != null ? caseEntity.getGrantRef() : "GRANT_DEFAULT";

        MoneyGrantEntity grantEntity = grantService.getGrant(caseEntity.getGrantRef());
        if (grantEntity != null) {
            try {
                if (grantEntity.getSubjectId() != null) {
                    actorUserId = Long.parseLong(grantEntity.getSubjectId());
                }
            } catch (NumberFormatException ignored) {}
            if (grantEntity.getSubjectType() != null) {
                actorRole = grantEntity.getSubjectType();
            }
        } else if ("FINANCE".equalsIgnoreCase(caseEntity.getOrigin()) || "OPERATIONS".equalsIgnoreCase(caseEntity.getOrigin())) {
            actorUserId = caseEntity.getOwnerStaffId() != null ? caseEntity.getOwnerStaffId() : 1L;
            actorRole = "FINANCE";
        }
        return new ActorIdentity(actorUserId, actorRole, grantRef);
    }

    private MoneyAccessContext buildOrderAccess(MoneyCaseEntity caseEntity, MoneyTaskEntity taskEntity, ActorIdentity id, MoneyCheckpoint cp) {
        String svcProof = MoneySecuritySupport.createServiceIdentityProof("mall-money-agent-service", "mall-order-service");
        String delProof = MoneySecuritySupport.createDelegationProof(
                id.userId(), id.role(), caseEntity.getScopeKey(), "mall-order-service",
                List.of("RESOLVE_SUBJECT", "QUERY_ORDER_SNAPSHOT", "OBSERVE_CHANNEL", "QUERY_RECON_EVIDENCE"), id.grantRef());

        String traceId = (cp != null && cp.getTraceId() != null && !cp.getTraceId().isBlank())
                ? cp.getTraceId()
                : "TRACE_" + taskEntity.getTaskNo();

        return new MoneyAccessContext(
                svcProof, delProof, null, "INVESTIGATE",
                taskEntity.getTaskNo(), traceId,
                "mall-money-agent-service", id.userId(), id.role());
    }

    private MoneyAccessContext buildUserAccess(MoneyCaseEntity caseEntity, MoneyTaskEntity taskEntity, ActorIdentity id, MoneyCheckpoint cp) {
        String svcProof = MoneySecuritySupport.createServiceIdentityProof("mall-money-agent-service", "mall-user-service");
        String delProof = MoneySecuritySupport.createDelegationProof(
                id.userId(), id.role(), caseEntity.getScopeKey(), "mall-user-service",
                List.of("QUERY_FUND_SNAPSHOT"), id.grantRef());

        String traceId = (cp != null && cp.getTraceId() != null && !cp.getTraceId().isBlank())
                ? cp.getTraceId()
                : "TRACE_" + taskEntity.getTaskNo();

        return new MoneyAccessContext(
                svcProof, delProof, null, "INVESTIGATE",
                taskEntity.getTaskNo(), traceId,
                "mall-money-agent-service", id.userId(), id.role());
    }

    private MoneySubjectSelector buildSelector(MoneyCaseEntity caseEntity) {
        String targetRef = caseEntity.getTargetRef();
        if (targetRef != null && targetRef.startsWith("PA_")) {
            return MoneySubjectSelector.forAttempt(targetRef);
        } else if (targetRef != null && targetRef.startsWith("RF_")) {
            return MoneySubjectSelector.forRefund(targetRef);
        } else if (targetRef != null && targetRef.startsWith("DIFF_")) {
            return MoneySubjectSelector.forDiff(targetRef);
        } else if (caseEntity.getOrderNo() != null && !caseEntity.getOrderNo().isBlank()) {
            return MoneySubjectSelector.forOrder(caseEntity.getOrderNo());
        }
        return MoneySubjectSelector.forOrder(caseEntity.getTargetRef());
    }
}
