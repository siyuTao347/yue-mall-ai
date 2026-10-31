package com.example.moneyagent.investigation;

import api.agent.money.*;
import com.example.moneyagent.persistence.entity.MoneyCaseEntity;
import com.example.moneyagent.persistence.entity.MoneyReportEntity;
import com.example.moneyagent.persistence.entity.MoneyTaskEntity;
import com.example.moneyagent.persistence.mapper.MoneyCaseMapper;
import com.example.moneyagent.persistence.mapper.MoneyReportMapper;
import com.example.moneyagent.persistence.mapper.MoneyTaskMapper;
import com.example.moneyagent.tools.OrderMoneyQueryClient;
import com.example.moneyagent.tools.UserFundQueryClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

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
    private final ObjectMapper objectMapper;

    public MoneyInvestigationGraph(OrderMoneyQueryClient orderQueryClient,
                                  UserFundQueryClient fundQueryClient,
                                  MoneyCaseMapper caseMapper,
                                  MoneyTaskMapper taskMapper,
                                  MoneyReportMapper reportMapper,
                                  ObjectMapper objectMapper) {
        this.orderQueryClient = orderQueryClient;
        this.fundQueryClient = fundQueryClient;
        this.caseMapper = caseMapper;
        this.taskMapper = taskMapper;
        this.reportMapper = reportMapper;
        this.objectMapper = objectMapper;
    }

    public MoneyReportEntity executeInvestigation(MoneyCaseEntity caseEntity, MoneyTaskEntity taskEntity) {
        log.info("开始执行资金调查 Graph, caseNo={}, taskNo={}, problemFamily={}",
                caseEntity.getCaseNo(), taskEntity.getTaskNo(), caseEntity.getProblemFamily());

        // 构造合法的内部受控调用凭据
        String svcProofOrder = MoneySecuritySupport.createServiceIdentityProof("mall-money-agent-service", "mall-order-service");
        String svcProofUser = MoneySecuritySupport.createServiceIdentityProof("mall-money-agent-service", "mall-user-service");
        String delProofOrder = MoneySecuritySupport.createDelegationProof(
                0L, "SYSTEM", caseEntity.getScopeKey(), "mall-order-service",
                List.of("RESOLVE_SUBJECT", "QUERY_ORDER_SNAPSHOT", "OBSERVE_CHANNEL", "QUERY_RECON_EVIDENCE"), "GRANT_INVESTIGATION");
        String delProofUser = MoneySecuritySupport.createDelegationProof(
                0L, "SYSTEM", caseEntity.getScopeKey(), "mall-user-service",
                List.of("QUERY_FUND_SNAPSHOT"), "GRANT_INVESTIGATION");

        MoneyAccessContext orderAccess = new MoneyAccessContext(
                svcProofOrder, delProofOrder, null, "INVESTIGATE",
                taskEntity.getTaskNo(), "TRACE_" + taskEntity.getTaskNo(),
                "mall-money-agent-service", 0L, "SYSTEM");

        MoneyAccessContext userAccess = new MoneyAccessContext(
                svcProofUser, delProofUser, null, "INVESTIGATE",
                taskEntity.getTaskNo(), "TRACE_" + taskEntity.getTaskNo(),
                "mall-money-agent-service", 0L, "SYSTEM");

        try {
            // 步骤 1：解析对象与签发范围证明
            MoneySubjectSelector selector = buildSelector(caseEntity);
            ResolveMoneySubjectResult resolveResult = orderQueryClient.resolveSubject(orderAccess, selector);
            if (resolveResult == null || !resolveResult.isSuccess()) {
                return failTask(caseEntity, taskEntity, "SUBJECT_RESOLVE_FAILED",
                        resolveResult != null ? resolveResult.getErrorMessage() : "无法解析调查对象");
            }

            String canonicalOrderNo = resolveResult.getCanonicalOrderNo();
            String scopeProofRef = resolveResult.getScopeProofRef();

            // 步骤 2：收集订单快照 (Order Snapshot)
            MoneySnapshotQuery snapshotQuery = MoneySnapshotQuery.forScope(
                    canonicalOrderNo != null ? "ORDER:" + canonicalOrderNo : caseEntity.getScopeKey());
            MoneyOrderSnapshot orderSnapshot = orderQueryClient.getSnapshot(orderAccess, snapshotQuery);

            // 步骤 3：收集渠道观察证据 (Channel Observation)
            MoneyChannelObservation channelObservation = null;
            if (resolveResult.getVerifiedAttemptRefs() != null && !resolveResult.getVerifiedAttemptRefs().isEmpty()) {
                String attemptNo = resolveResult.getVerifiedAttemptRefs().get(0);
                MoneyChannelQuery query = MoneyChannelQuery.forAttempt("ORDER:" + canonicalOrderNo, attemptNo, null);
                channelObservation = orderQueryClient.observeChannel(orderAccess, query);
            } else if (resolveResult.getVerifiedRefundRefs() != null && !resolveResult.getVerifiedRefundRefs().isEmpty()) {
                String refundNo = resolveResult.getVerifiedRefundRefs().get(0);
                MoneyChannelQuery query = MoneyChannelQuery.forRefund("ORDER:" + canonicalOrderNo, refundNo, null);
                channelObservation = orderQueryClient.observeChannel(orderAccess, query);
            }

            // 步骤 4：收集平台资金台账 (Fund Snapshot)
            MoneyFundSnapshot fundSnapshot = null;
            if (scopeProofRef != null && !scopeProofRef.isBlank()) {
                MoneyFundQuery fundQuery = new MoneyFundQuery(
                        scopeProofRef,
                        canonicalOrderNo,
                        resolveResult.getVerifiedAttemptRefs(),
                        resolveResult.getVerifiedRefundRefs(),
                        Collections.emptyList()
                );
                fundSnapshot = fundQueryClient.getSnapshot(userAccess, fundQuery);
            }

            // 步骤 5：综合证据裁决与报告装配
            String outcome = "VERIFIED_NORMAL";
            StringBuilder summary = new StringBuilder();
            summary.append("### 资金调查结果摘要\n\n");
            summary.append("- **问题类型**：").append(caseEntity.getProblemFamily()).append("\n");
            summary.append("- **标准订单号**：").append(canonicalOrderNo != null ? canonicalOrderNo : "无").append("\n");

            if (channelObservation != null) {
                summary.append("- **外部渠道状态**：").append(channelObservation.getRawStatus())
                        .append("（存在性：").append(channelObservation.getExists()).append("，金额：")
                        .append(channelObservation.getAmount()).append(" ").append(channelObservation.getCurrency()).append("）\n");
            }

            if (orderSnapshot != null && orderSnapshot.getPaymentStatus() != null) {
                summary.append("- **平台支付状态**：").append(orderSnapshot.getPaymentStatus()).append("\n");
            }

            if (fundSnapshot != null && fundSnapshot.getTransactions() != null) {
                summary.append("- **平台资金流水数**：").append(fundSnapshot.getTransactions().size()).append("\n");
            }

            // 规则裁决
            if ("PAYMENT_CONFIRMATION".equalsIgnoreCase(caseEntity.getProblemFamily())) {
                if (channelObservation != null && Boolean.TRUE.equals(channelObservation.getExists())
                        && "SUCCESS".equalsIgnoreCase(channelObservation.getRawStatus())
                        && !"PAID".equalsIgnoreCase(orderSnapshot != null ? orderSnapshot.getPaymentStatus() : null)) {
                    outcome = "NEEDS_REVIEW";
                    summary.append("\n> **异常裁决**：外部支付渠道已扣款成功，但平台支付订单仍未更新为已支付，存在掉单差异，建议运营/财务人工复核或触发受控补确认！\n");
                } else if (channelObservation != null && (!Boolean.TRUE.equals(channelObservation.getExists()) || "NOT_FOUND".equalsIgnoreCase(channelObservation.getRawStatus()))) {
                    outcome = "VERIFIED_NORMAL";
                    summary.append("\n> **正常裁决**：外部支付渠道确认该笔交易未付款或已关闭，无重复扣款，状态正常。\n");
                }
            } else if ("REFUND_STATUS".equalsIgnoreCase(caseEntity.getProblemFamily())) {
                if (channelObservation != null && Boolean.TRUE.equals(channelObservation.getExists())
                        && "SUCCESS".equalsIgnoreCase(channelObservation.getRawStatus())) {
                    outcome = "VERIFIED_RESOLVED";
                    summary.append("\n> **退款裁决**：外部支付渠道已完成退款出账，退款资金已成功释放，状态正常。\n");
                } else {
                    outcome = "NEEDS_REVIEW";
                    summary.append("\n> **退款裁决**：退款在外部渠道仍处于处理中或未完成，需保持观察或联系渠道。\n");
                }
            }

            // 步骤 6：持久化报告
            String reportNo = "RPT_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
            Map<String, Object> factsPayload = new LinkedHashMap<>();
            factsPayload.put("orderSnapshot", orderSnapshot);
            factsPayload.put("channelObservation", channelObservation);
            factsPayload.put("fundSnapshot", fundSnapshot);

            String factsJson = objectMapper.writeValueAsString(factsPayload);
            String reportHash = MoneySecuritySupport.computeFingerprint(factsJson);

            MoneyReportEntity reportEntity = MoneyReportEntity.builder()
                    .reportNo(reportNo)
                    .caseNo(caseEntity.getCaseNo())
                    .taskNo(taskEntity.getTaskNo())
                    .revision(1L)
                    .schemaVersion(1)
                    .basedOnFingerprint(orderSnapshot != null ? orderSnapshot.getSnapshotFingerprint() : reportHash)
                    .internalJson(factsJson)
                    .reportHash(reportHash)
                    .outcome(outcome)
                    .ruleVersion("RULE_V1")
                    .promptVersion("PROMPT_V1")
                    .modelVersion("MOCK_ANALYZER_V1")
                    .createdAt(LocalDateTime.now(ZoneOffset.UTC))
                    .build();

            reportMapper.insert(reportEntity);

            // 更新任务状态为 COMPLETED
            taskEntity.setState("COMPLETED");
            taskEntity.setFinishedAt(LocalDateTime.now(ZoneOffset.UTC));
            taskEntity.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
            taskMapper.updateById(taskEntity);

            // 更新案件状态
            caseEntity.setOutcome(outcome);
            caseEntity.setLatestReportNo(reportNo);
            caseEntity.setCurrentTaskNo(taskEntity.getTaskNo());
            caseEntity.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
            caseMapper.updateById(caseEntity);

            log.info("资金调查 Graph 完成, caseNo={}, taskNo={}, reportNo={}, outcome={}",
                    caseEntity.getCaseNo(), taskEntity.getTaskNo(), reportNo, outcome);

            return reportEntity;
        } catch (Exception e) {
            log.error("资金调查 Graph 执行异常, caseNo={}, taskNo={}", caseEntity.getCaseNo(), taskEntity.getTaskNo(), e);
            return failTask(caseEntity, taskEntity, "INVESTIGATION_ERROR", e.getMessage());
        }
    }

    private MoneyReportEntity failTask(MoneyCaseEntity caseEntity, MoneyTaskEntity taskEntity, String errCode, String errMsg) {
        taskEntity.setState("FAILED");
        taskEntity.setLastErrorCode(errCode);
        taskEntity.setFinishedAt(LocalDateTime.now(ZoneOffset.UTC));
        taskEntity.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        taskMapper.updateById(taskEntity);

        caseEntity.setOutcome("INCONCLUSIVE");
        caseEntity.setCloseReason(errMsg);
        caseEntity.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        caseMapper.updateById(caseEntity);

        return null;
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

