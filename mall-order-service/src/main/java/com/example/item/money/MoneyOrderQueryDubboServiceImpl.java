package com.example.item.money;

import api.agent.money.*;
import api.exception.ForbiddenException;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.item.entity.PaymentOrder;
import com.example.item.entity.TradeOrder;
import com.example.item.entity.TradeOrchestrationTask;
import com.example.item.mapper.PaymentOrderMapper;
import com.example.item.mapper.TradeOrderMapper;
import com.example.item.mapper.TradeOrchestrationTaskMapper;
import com.example.item.payment.domain.PaymentAttempt;
import com.example.item.payment.domain.PaymentFinancialEvent;
import com.example.item.payment.domain.PaymentRefundOrder;
import com.example.item.payment.mapper.PaymentAttemptMapper;
import com.example.item.payment.mapper.PaymentFinancialEventMapper;
import com.example.item.payment.mapper.PaymentRefundOrderMapper;
import com.example.item.reconciliation.domain.ReconDiff;
import com.example.item.reconciliation.mapper.ReconDiffMapper;
import org.apache.dubbo.config.annotation.DubboService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;

@DubboService(timeout = 3000, retries = 0)
public class MoneyOrderQueryDubboServiceImpl implements MoneyOrderQueryDubboService {

    private final MoneySubjectResolver subjectResolver;
    private final MoneyChannelObservationService channelObservationService;
    private final MoneyReconEvidenceService reconEvidenceService;
    private final TradeOrderMapper orderMapper;
    private final PaymentOrderMapper paymentOrderMapper;
    private final PaymentAttemptMapper attemptMapper;
    private final PaymentRefundOrderMapper refundMapper;
    private final PaymentFinancialEventMapper financialEventMapper;
    private final TradeOrchestrationTaskMapper orchestrationMapper;
    private final ReconDiffMapper diffMapper;

    public MoneyOrderQueryDubboServiceImpl(MoneySubjectResolver subjectResolver,
                                           MoneyChannelObservationService channelObservationService,
                                           MoneyReconEvidenceService reconEvidenceService,
                                           TradeOrderMapper orderMapper,
                                           PaymentOrderMapper paymentOrderMapper,
                                           PaymentAttemptMapper attemptMapper,
                                           PaymentRefundOrderMapper refundMapper,
                                           PaymentFinancialEventMapper financialEventMapper,
                                           TradeOrchestrationTaskMapper orchestrationMapper,
                                           ReconDiffMapper diffMapper) {
        this.subjectResolver = subjectResolver;
        this.channelObservationService = channelObservationService;
        this.reconEvidenceService = reconEvidenceService;
        this.orderMapper = orderMapper;
        this.paymentOrderMapper = paymentOrderMapper;
        this.attemptMapper = attemptMapper;
        this.refundMapper = refundMapper;
        this.financialEventMapper = financialEventMapper;
        this.orchestrationMapper = orchestrationMapper;
        this.diffMapper = diffMapper;
    }

    @Override
    public ResolveMoneySubjectResult resolveSubject(MoneyAccessContext access, MoneySubjectSelector selector) {
        return subjectResolver.resolve(access, selector);
    }

    @Override
    public MoneyOrderSnapshot getSnapshot(MoneyAccessContext access, MoneySnapshotQuery query) {
        if (access == null) {
            throw new ForbiddenException(MoneyErrorCodes.MONEY_FORBIDDEN, "访问凭据 (access) 缺失，拒绝查询订单快照");
        }

        String scopeKey = query != null ? query.getScopeKey() : null;
        MoneySecuritySupport.VerifiedAccess verified;
        try {
            verified = MoneySecuritySupport.verifyEntryAccess(
                    access, "mall-order-service", "QUERY_ORDER_SNAPSHOT", scopeKey);
        } catch (Exception e) {
            throw new ForbiddenException(MoneyErrorCodes.MONEY_FORBIDDEN, "访问凭据校验未通过: " + e.getMessage());
        }

        if (query == null || (query.getScopeKey() == null && (query.getSubjectRefs() == null || query.getSubjectRefs().isEmpty()))) {
            return new MoneyOrderSnapshot(null, null, null, null,
                    List.of(), List.of(), List.of(), null, List.of(),
                    new CoverageInfo(Map.of("ORDER", MoneyCoverageStatus.NOT_FOUND.name()), false, false, null, "Query empty"),
                    "", Instant.now().toString());
        }

        String orderNo = extractOrderNo(query);

        // 严格订单归属鉴权
        if (orderNo != null && !orderNo.isBlank() && orderMapper != null) {
            TradeOrder tradeOrder = orderMapper.selectOne(new LambdaQueryWrapper<TradeOrder>()
                    .eq(TradeOrder::getOrderNo, orderNo));
            if (tradeOrder != null) {
                String role = verified.getActorRole();
                Long userId = verified.getActorUserId();
                if ("BUYER".equalsIgnoreCase(role)) {
                    if (userId == null || !Objects.equals(tradeOrder.getBuyerId(), userId)) {
                        throw new ForbiddenException(MoneyErrorCodes.MONEY_FORBIDDEN, "无权查看该订单事实（买家主体不匹配）");
                    }
                } else if ("SELLER".equalsIgnoreCase(role)) {
                    if (userId == null || !Objects.equals(tradeOrder.getSellerId(), userId)) {
                        throw new ForbiddenException(MoneyErrorCodes.MONEY_FORBIDDEN, "无权查看该订单事实（商家主体不匹配）");
                    }
                } else if (!isStaffOrFinance(role)) {
                    throw new ForbiddenException(MoneyErrorCodes.MONEY_FORBIDDEN, "未授权的访问角色: " + role);
                }
            }
        }

        int pageSize = query.getPageSize() != null ? Math.min(Math.max(query.getPageSize(), 1), 100) : 50;

        scopeKey = query.getScopeKey() != null ? query.getScopeKey() : "ORDER:" + orderNo;

        // 解析并验证游标
        CursorPayload cursorPayload;
        try {
            cursorPayload = parseCursor(query.getCursor(), scopeKey);
        } catch (IllegalArgumentException e) {
            return MoneyOrderSnapshot.unavailable(scopeKey, orderNo, MoneyErrorCodes.MONEY_PARAM_INVALID + ": " + e.getMessage());
        }

        if (cursorPayload == null) {
            // 首轮查询：初始化水位线与集合状态
            long hwAttempt = getMaxAttemptId(orderNo);
            long hwRefund = getMaxRefundId(orderNo);
            long hwEvent = getMaxEventId(orderNo);
            long hwDiff = getMaxDiffId(orderNo);

            cursorPayload = new CursorPayload();
            cursorPayload.schemaVersion = 1;
            cursorPayload.scopeKey = scopeKey;
            cursorPayload.expiresAt = System.currentTimeMillis() + 600000;
            cursorPayload.attemptHighWaterId = hwAttempt > 0 ? hwAttempt : Long.MAX_VALUE;
            cursorPayload.refundHighWaterId = hwRefund > 0 ? hwRefund : Long.MAX_VALUE;
            cursorPayload.eventHighWaterId = hwEvent > 0 ? hwEvent : Long.MAX_VALUE;
            cursorPayload.diffHighWaterId = hwDiff > 0 ? hwDiff : Long.MAX_VALUE;
            cursorPayload.attemptCompleted = false;
            cursorPayload.refundCompleted = false;
            cursorPayload.eventCompleted = false;
            cursorPayload.diffCompleted = false;
        }

        PaymentOrder paymentOrder = orderNo != null
                ? paymentOrderMapper.selectOne(new LambdaQueryWrapper<PaymentOrder>().eq(PaymentOrder::getOrderNo, orderNo))
                : null;

        // 1. 查询支付尝试 (按不可变 ID 升序，id > afterId AND id <= highWaterId，Limit + 1 探测截断)
        List<PaymentAttempt> attempts = Collections.emptyList();
        boolean attemptsTruncated = false;
        long nextAttemptAfterId = cursorPayload.attemptAfterId;
        boolean nextAttemptCompleted = cursorPayload.attemptCompleted;

        if (orderNo != null && !cursorPayload.attemptCompleted && cursorPayload.attemptHighWaterId > cursorPayload.attemptAfterId) {
            List<PaymentAttempt> rawAttempts = attemptMapper.selectList(new LambdaQueryWrapper<PaymentAttempt>()
                    .eq(PaymentAttempt::getOrderNo, orderNo)
                    .gt(PaymentAttempt::getId, cursorPayload.attemptAfterId)
                    .le(PaymentAttempt::getId, cursorPayload.attemptHighWaterId)
                    .orderByAsc(PaymentAttempt::getId)
                    .last("LIMIT " + (pageSize + 1)));
            attemptsTruncated = rawAttempts != null && rawAttempts.size() > pageSize;
            attempts = attemptsTruncated ? rawAttempts.subList(0, pageSize) : (rawAttempts != null ? rawAttempts : Collections.emptyList());
            if (!attempts.isEmpty()) {
                nextAttemptAfterId = attempts.get(attempts.size() - 1).getId();
            }
            nextAttemptCompleted = !attemptsTruncated;
        }

        // 2. 查询退款单 (Limit + 1 探测截断)
        List<PaymentRefundOrder> refunds = Collections.emptyList();
        boolean refundsTruncated = false;
        long nextRefundAfterId = cursorPayload.refundAfterId;
        boolean nextRefundCompleted = cursorPayload.refundCompleted;

        if (orderNo != null && !cursorPayload.refundCompleted && cursorPayload.refundHighWaterId > cursorPayload.refundAfterId) {
            List<PaymentRefundOrder> rawRefunds = refundMapper.selectList(new LambdaQueryWrapper<PaymentRefundOrder>()
                    .eq(PaymentRefundOrder::getOrderNo, orderNo)
                    .gt(PaymentRefundOrder::getId, cursorPayload.refundAfterId)
                    .le(PaymentRefundOrder::getId, cursorPayload.refundHighWaterId)
                    .orderByAsc(PaymentRefundOrder::getId)
                    .last("LIMIT " + (pageSize + 1)));
            refundsTruncated = rawRefunds != null && rawRefunds.size() > pageSize;
            refunds = refundsTruncated ? rawRefunds.subList(0, pageSize) : (rawRefunds != null ? rawRefunds : Collections.emptyList());
            if (!refunds.isEmpty()) {
                nextRefundAfterId = refunds.get(refunds.size() - 1).getId();
            }
            nextRefundCompleted = !refundsTruncated;
        }

        // 3. 查询金融事件 (Limit + 1 探测截断)
        List<PaymentFinancialEvent> events = Collections.emptyList();
        boolean eventsTruncated = false;
        long nextEventAfterId = cursorPayload.eventAfterId;
        boolean nextEventCompleted = cursorPayload.eventCompleted;

        if (orderNo != null && !cursorPayload.eventCompleted && cursorPayload.eventHighWaterId > cursorPayload.eventAfterId) {
            List<PaymentFinancialEvent> rawEvents = financialEventMapper.selectList(new LambdaQueryWrapper<PaymentFinancialEvent>()
                    .eq(PaymentFinancialEvent::getOrderNo, orderNo)
                    .gt(PaymentFinancialEvent::getId, cursorPayload.eventAfterId)
                    .le(PaymentFinancialEvent::getId, cursorPayload.eventHighWaterId)
                    .orderByAsc(PaymentFinancialEvent::getId)
                    .last("LIMIT " + (pageSize + 1)));
            eventsTruncated = rawEvents != null && rawEvents.size() > pageSize;
            events = eventsTruncated ? rawEvents.subList(0, pageSize) : (rawEvents != null ? rawEvents : Collections.emptyList());
            if (!events.isEmpty()) {
                nextEventAfterId = events.get(events.size() - 1).getId();
            }
            nextEventCompleted = !eventsTruncated;
        }

        // 4. 查询编排任务
        TradeOrchestrationTask orchTask = orderNo != null
                ? orchestrationMapper.selectOne(new LambdaQueryWrapper<TradeOrchestrationTask>()
                .eq(TradeOrchestrationTask::getBizNo, orderNo)
                .orderByDesc(TradeOrchestrationTask::getId)
                .last("LIMIT 1"))
                : null;

        // 5. 查询对账差异 (Limit + 1 探测截断)
        List<ReconDiff> diffs = Collections.emptyList();
        boolean diffsTruncated = false;
        long nextDiffAfterId = cursorPayload.diffAfterId;
        boolean nextDiffCompleted = cursorPayload.diffCompleted;

        if (orderNo != null && !cursorPayload.diffCompleted && cursorPayload.diffHighWaterId > cursorPayload.diffAfterId) {
            List<ReconDiff> rawDiffs = diffMapper.selectList(new LambdaQueryWrapper<ReconDiff>()
                    .eq(ReconDiff::getOrderNo, orderNo)
                    .gt(ReconDiff::getId, cursorPayload.diffAfterId)
                    .le(ReconDiff::getId, cursorPayload.diffHighWaterId)
                    .orderByAsc(ReconDiff::getId)
                    .last("LIMIT " + (pageSize + 1)));
            diffsTruncated = rawDiffs != null && rawDiffs.size() > pageSize;
            diffs = diffsTruncated ? rawDiffs.subList(0, pageSize) : (rawDiffs != null ? rawDiffs : Collections.emptyList());
            if (!diffs.isEmpty()) {
                nextDiffAfterId = diffs.get(diffs.size() - 1).getId();
            }
            nextDiffCompleted = !diffsTruncated;
        }

        // 组装快照子对象
        List<PaymentAttemptSnapshot> attemptSnapshots = new ArrayList<>();
        if (attempts != null) {
            for (PaymentAttempt a : attempts) {
                attemptSnapshots.add(new PaymentAttemptSnapshot(
                        a.getAttemptNo(),
                        a.getPaymentNo(),
                        a.getOrderNo(),
                        a.getAttemptSeq(),
                        a.getPaymentMethod(),
                        a.getProfileId(),
                        a.getProfileVersion(),
                        a.getChannelMerchantNo(),
                        a.getChannelTradeNo(),
                        a.getOutTradeNo(),
                        a.getAmount(),
                        a.getCurrency(),
                        a.getStatus(),
                        a.getAllocationStatus(),
                        formatIso(a.getChannelSuccessAt()),
                        a.getChannelBusinessDate() != null ? a.getChannelBusinessDate().toString() : null,
                        formatIso(a.getLocalConfirmedAt()),
                        a.getVersion()
                ));
            }
        }

        List<PaymentRefundOrderSnapshot> refundSnapshots = new ArrayList<>();
        if (refunds != null) {
            for (PaymentRefundOrder r : refunds) {
                refundSnapshots.add(new PaymentRefundOrderSnapshot(
                        r.getRefundNo(),
                        r.getOriginalAttemptNo(),
                        r.getOrderNo(),
                        r.getAmount(),
                        r.getCurrency(),
                        r.getReason(),
                        r.getStatus(),
                        r.getChannelRefundNo(),
                        r.getFundTaskNo(),
                        formatIso(r.getAcceptedAt()),
                        formatIso(r.getSuccessAt()),
                        r.getVersion()
                ));
            }
        }

        List<PaymentFinancialEventSnapshot> eventSnapshots = new ArrayList<>();
        if (events != null) {
            for (PaymentFinancialEvent e : events) {
                eventSnapshots.add(new PaymentFinancialEventSnapshot(
                        e.getEventNo(),
                        e.getProfileId(),
                        e.getProfileVersion(),
                        e.getChannelMerchantNo(),
                        e.getAccountId(),
                        e.getEventType(),
                        e.getEventStage(),
                        e.getBizType(),
                        e.getBizNo(),
                        e.getOrderNo(),
                        e.getAmount(),
                        e.getFeeAmount(),
                        e.getCurrency(),
                        e.getChannelBusinessDate() != null ? e.getChannelBusinessDate().toString() : null,
                        formatIso(e.getOccurredAt()),
                        formatIso(e.getRecordedAt())
                ));
            }
        }

        TradeOrchestrationSnapshot orchSnapshot = orchTask != null
                ? new TradeOrchestrationSnapshot(
                orchTask.getTaskNo(),
                orchTask.getBizNo(),
                orchTask.getBizType(),
                orchTask.getStatus(),
                orchTask.getTaskType(),
                null)
                : null;

        List<ReconDiffSummarySnapshot> diffSnapshots = new ArrayList<>();
        if (diffs != null) {
            for (ReconDiff d : diffs) {
                diffSnapshots.add(new ReconDiffSummarySnapshot(
                        d.getDiffNo(),
                        d.getDiffType(),
                        d.getBizKey(),
                        d.getOrderNo(),
                        d.getAttemptNo(),
                        d.getRefundNo(),
                        d.getChannelAmount(),
                        d.getLocalAmount(),
                        d.getStatus()
                ));
            }
        }

        boolean hasMore = attemptsTruncated || refundsTruncated || eventsTruncated || diffsTruncated ||
                (!nextAttemptCompleted || !nextRefundCompleted || !nextEventCompleted || !nextDiffCompleted);

        CursorPayload nextPayload = new CursorPayload();
        nextPayload.schemaVersion = 1;
        nextPayload.scopeKey = scopeKey;
        nextPayload.expiresAt = System.currentTimeMillis() + 600000;
        nextPayload.attemptAfterId = nextAttemptAfterId;
        nextPayload.attemptHighWaterId = cursorPayload.attemptHighWaterId;
        nextPayload.attemptCompleted = nextAttemptCompleted;
        nextPayload.refundAfterId = nextRefundAfterId;
        nextPayload.refundHighWaterId = cursorPayload.refundHighWaterId;
        nextPayload.refundCompleted = nextRefundCompleted;
        nextPayload.eventAfterId = nextEventAfterId;
        nextPayload.eventHighWaterId = cursorPayload.eventHighWaterId;
        nextPayload.eventCompleted = nextEventCompleted;
        nextPayload.diffAfterId = nextDiffAfterId;
        nextPayload.diffHighWaterId = cursorPayload.diffHighWaterId;
        nextPayload.diffCompleted = nextDiffCompleted;

        String nextCursor = hasMore ? buildSignedCursor(nextPayload) : null;

        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("ORDER", paymentOrder != null ? MoneyCoverageStatus.OK.name() : MoneyCoverageStatus.NOT_FOUND.name());
        sources.put("ATTEMPTS", attemptsTruncated ? MoneyCoverageStatus.TRUNCATED.name()
                : (attemptSnapshots.isEmpty() ? MoneyCoverageStatus.NOT_FOUND.name() : MoneyCoverageStatus.OK.name()));
        sources.put("REFUNDS", refundsTruncated ? MoneyCoverageStatus.TRUNCATED.name()
                : (refundSnapshots.isEmpty() ? MoneyCoverageStatus.NOT_FOUND.name() : MoneyCoverageStatus.OK.name()));
        sources.put("FINANCIAL_EVENTS", eventsTruncated ? MoneyCoverageStatus.TRUNCATED.name()
                : (eventSnapshots.isEmpty() ? MoneyCoverageStatus.NOT_FOUND.name() : MoneyCoverageStatus.OK.name()));
        sources.put("DIFFS", diffsTruncated ? MoneyCoverageStatus.TRUNCATED.name()
                : (diffSnapshots.isEmpty() ? MoneyCoverageStatus.NOT_FOUND.name() : MoneyCoverageStatus.OK.name()));

        CoverageInfo coverage = new CoverageInfo(sources, !hasMore, hasMore, nextCursor, hasMore ? "TRUNCATED" : "COMPLETE");

        // 规范化 SHA-256 事实指纹计算 (涵盖所有实质内容变化)
        String canonicalFacts = buildCanonicalFactsSummary(orderNo, paymentOrder, attemptSnapshots,
                refundSnapshots, eventSnapshots, orchSnapshot, diffSnapshots, coverage);
        String fingerprint = MoneySecuritySupport.computeFingerprint(canonicalFacts);

        return new MoneyOrderSnapshot(
                query.getScopeKey() != null ? query.getScopeKey() : "ORDER:" + orderNo,
                orderNo,
                paymentOrder != null ? paymentOrder.getPaymentNo() : null,
                paymentOrder != null ? paymentOrder.getStatus() : null,
                attemptSnapshots,
                refundSnapshots,
                eventSnapshots,
                orchSnapshot,
                diffSnapshots,
                coverage,
                fingerprint,
                Instant.now().toString()
        );
    }

    @Override
    public MoneyChannelObservation observeChannel(MoneyAccessContext access, MoneyChannelQuery query) {
        if (access == null) {
            throw new ForbiddenException(MoneyErrorCodes.MONEY_FORBIDDEN, "访问凭据 (access) 缺失，拒绝观察渠道");
        }
        MoneySecuritySupport.VerifiedAccess verified;
        try {
            verified = MoneySecuritySupport.verifyEntryAccess(
                    access, "mall-order-service", "OBSERVE_CHANNEL", query != null ? query.getScopeKey() : null);
        } catch (Exception e) {
            throw new ForbiddenException(MoneyErrorCodes.MONEY_FORBIDDEN, "访问凭据校验未通过: " + e.getMessage());
        }

        if (!isStaffOrFinance(verified.getActorRole())) {
            throw new ForbiddenException(MoneyErrorCodes.MONEY_FORBIDDEN, "仅财务或运营管理角色获准执行渠道主动取证");
        }

        return channelObservationService.observeChannel(access, query);
    }

    @Override
    public MoneyReconEvidence getReconEvidence(MoneyAccessContext access, MoneyReconQuery query) {
        if (access == null) {
            throw new ForbiddenException(MoneyErrorCodes.MONEY_FORBIDDEN, "访问凭据 (access) 缺失，拒绝查询对账证据");
        }
        String scopeKey = (query != null && query.getDiffNo() != null) ? "DIFF:" + query.getDiffNo() : null;
        MoneySecuritySupport.VerifiedAccess verified;
        try {
            verified = MoneySecuritySupport.verifyEntryAccess(
                    access, "mall-order-service", "QUERY_RECON_EVIDENCE", scopeKey);
        } catch (Exception e) {
            throw new ForbiddenException(MoneyErrorCodes.MONEY_FORBIDDEN, "访问凭据校验未通过: " + e.getMessage());
        }

        if (!isStaffOrFinance(verified.getActorRole())) {
            throw new ForbiddenException(MoneyErrorCodes.MONEY_FORBIDDEN, "仅财务或运营管理角色获准查看内部对账差异取证");
        }

        return reconEvidenceService.getEvidence(access, query);
    }

    @Override
    public IssueFundScopeProofResult issueFundScopeProof(MoneyAccessContext access, IssueFundScopeProofRequest request) {
        if (access == null) {
            return IssueFundScopeProofResult.fail(MoneyErrorCodes.MONEY_FORBIDDEN, "访问凭据 (access) 缺失，拒绝签发");
        }
        if (request == null) {
            return IssueFundScopeProofResult.fail(MoneyErrorCodes.MONEY_PARAM_INVALID, "签发请求参数不能为空");
        }

        MoneySecuritySupport.VerifiedAccess verified;
        try {
            verified = MoneySecuritySupport.verifyEntryAccess(
                    access, "mall-order-service", "ISSUE_FUND_SCOPE_PROOF", request.getScopeKey());
        } catch (Exception e) {
            return IssueFundScopeProofResult.fail(MoneyErrorCodes.MONEY_FORBIDDEN, "凭据校验未通过: " + e.getMessage());
        }

        List<String> attempts = request.getAttemptRefs() != null ? request.getAttemptRefs() : Collections.emptyList();
        List<String> refunds = request.getRefundRefs() != null ? request.getRefundRefs() : Collections.emptyList();
        int totalRefs = attempts.size() + refunds.size();
        if (totalRefs > 50) {
            return IssueFundScopeProofResult.fail(MoneyErrorCodes.MONEY_PARAM_INVALID, "单批范围证明引用数不能超过 50 个，当前总数: " + totalRefs);
        }

        String targetOrderNo = request.getOrderNo();
        if (targetOrderNo != null && !targetOrderNo.isBlank()) {
            TradeOrder order = orderMapper.selectOne(new LambdaQueryWrapper<TradeOrder>()
                    .eq(TradeOrder::getOrderNo, targetOrderNo.trim()));
            if (order == null) {
                return IssueFundScopeProofResult.fail(MoneyErrorCodes.MONEY_SUBJECT_NOT_FOUND, "订单不存在: " + targetOrderNo);
            }

            String role = verified.getActorRole();
            Long uid = verified.getActorUserId();
            if ("BUYER".equalsIgnoreCase(role)) {
                if (uid == null || !Objects.equals(order.getBuyerId(), uid)) {
                    return IssueFundScopeProofResult.fail(MoneyErrorCodes.MONEY_FORBIDDEN, "无权访问此订单（买家主体不匹配）");
                }
            } else if ("SELLER".equalsIgnoreCase(role)) {
                if (uid == null || !Objects.equals(order.getSellerId(), uid)) {
                    return IssueFundScopeProofResult.fail(MoneyErrorCodes.MONEY_FORBIDDEN, "无权访问此订单（商家主体不匹配）");
                }
            } else if ("CUSTOMER_STAFF".equalsIgnoreCase(role)) {
                if (uid != null && !Objects.equals(order.getBuyerId(), uid)) {
                    return IssueFundScopeProofResult.fail(MoneyErrorCodes.MONEY_FORBIDDEN, "客服坐席主体与当前订单买家不匹配");
                }
            } else if (!isStaffOrFinance(role)) {
                return IssueFundScopeProofResult.fail(MoneyErrorCodes.MONEY_FORBIDDEN, "未授权的访问角色: " + role);
            }

            for (String attNo : attempts) {
                PaymentAttempt att = attemptMapper.selectOne(new LambdaQueryWrapper<PaymentAttempt>()
                        .eq(PaymentAttempt::getAttemptNo, attNo));
                if (att == null || !targetOrderNo.equals(att.getOrderNo())) {
                    return IssueFundScopeProofResult.fail(MoneyErrorCodes.MONEY_SUBJECT_MISMATCH, "支付尝试 " + attNo + " 不属于订单 " + targetOrderNo);
                }
            }
            for (String refNo : refunds) {
                PaymentRefundOrder ref = refundMapper.selectOne(new LambdaQueryWrapper<PaymentRefundOrder>()
                        .eq(PaymentRefundOrder::getRefundNo, refNo));
                if (ref == null || !targetOrderNo.equals(ref.getOrderNo())) {
                    return IssueFundScopeProofResult.fail(MoneyErrorCodes.MONEY_SUBJECT_MISMATCH, "退款单 " + refNo + " 不属于订单 " + targetOrderNo);
                }
            }

            String scopeKey = request.getScopeKey() != null ? request.getScopeKey() : "ORDER:" + targetOrderNo;
            String raw = targetOrderNo + ":" + order.getBuyerId() + ":" + order.getSellerId() + ":" + attempts.size() + ":" + refunds.size();
            String fp = MoneySecuritySupport.computeFingerprint(raw);
            String targetAudience = request.getTargetAudience() != null ? request.getTargetAudience() : "mall-user-service,mall-money-services";
            String proof = MoneySecuritySupport.createScopeProof(
                    targetOrderNo,
                    scopeKey,
                    order.getBuyerId(),
                    order.getSellerId(),
                    request.getGrantRef() != null ? request.getGrantRef() : verified.getGrantId(),
                    request.getAllowedActions() != null ? request.getAllowedActions() : List.of("READ", "QUERY_FUND"),
                    attempts,
                    refunds,
                    fp,
                    targetAudience
            );
            return IssueFundScopeProofResult.builder()
                    .success(true)
                    .orderNo(targetOrderNo)
                    .scopeKey(scopeKey)
                    .scopeProofRef(proof)
                    .scopeFingerprint(fp)
                    .verifiedAttempts(attempts)
                    .verifiedRefunds(refunds)
                    .build();
        } else if (request.getScopeKey() != null && request.getScopeKey().startsWith("RECON:")) {
            if (!isStaffOrFinance(verified.getActorRole())) {
                return IssueFundScopeProofResult.fail(MoneyErrorCodes.MONEY_FORBIDDEN, "仅财务人员获准签发未关联订单的对账范围证明");
            }
            String fp = MoneySecuritySupport.computeFingerprint(request.getScopeKey());
            String targetAudience = request.getTargetAudience() != null ? request.getTargetAudience() : "mall-user-service,mall-money-services";
            String proof = MoneySecuritySupport.createScopeProof(
                    null,
                    request.getScopeKey(),
                    null,
                    null,
                    request.getGrantRef() != null ? request.getGrantRef() : verified.getGrantId(),
                    request.getAllowedActions() != null ? request.getAllowedActions() : List.of("READ", "QUERY_FUND"),
                    attempts,
                    refunds,
                    fp,
                    targetAudience
            );
            return IssueFundScopeProofResult.builder()
                    .success(true)
                    .scopeKey(request.getScopeKey())
                    .scopeProofRef(proof)
                    .scopeFingerprint(fp)
                    .verifiedAttempts(attempts)
                    .verifiedRefunds(refunds)
                    .build();
        } else {
            return IssueFundScopeProofResult.fail(MoneyErrorCodes.MONEY_PARAM_INVALID, "必须指定有效的 orderNo 或 RECON scopeKey");
        }
    }

    private static String extractOrderNo(MoneySnapshotQuery query) {
        if (query.getScopeKey() != null && query.getScopeKey().startsWith("ORDER:")) {
            return query.getScopeKey().substring("ORDER:".length()).trim();
        }
        if (query.getSubjectRefs() != null) {
            for (String ref : query.getSubjectRefs()) {
                if (ref != null && ref.startsWith("ORDER:")) {
                    return ref.substring("ORDER:".length()).trim();
                } else if (ref != null && ref.startsWith("T")) {
                    return ref.trim();
                }
            }
        }
        return query.getScopeKey();
    }

    private static String formatIso(java.time.LocalDateTime ldt) {
        if (ldt == null) return null;
        // 数据库配置为 Asia/Shanghai，显式转换为正确的 UTC Instant ISO 格式
        return ldt.atZone(ZoneId.of("Asia/Shanghai")).toInstant().toString();
    }

    private boolean isStaffOrFinance(String role) {
        if (role == null) return false;
        String r = role.toUpperCase();
        return "ADMIN".equals(r) || "FINANCE".equals(r) || "CUSTOMER_STAFF".equals(r) || "SYSTEM".equals(r);
    }

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    static {
        OBJECT_MAPPER.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    public static class CursorPayload {
        public int schemaVersion = 1;
        public String scopeKey;
        public long expiresAt;
        public long attemptAfterId;
        public long attemptHighWaterId;
        public boolean attemptCompleted;
        public long refundAfterId;
        public long refundHighWaterId;
        public boolean refundCompleted;
        public long eventAfterId;
        public long eventHighWaterId;
        public boolean eventCompleted;
        public long diffAfterId;
        public long diffHighWaterId;
        public boolean diffCompleted;
    }

    private long getMaxAttemptId(String orderNo) {
        if (attemptMapper == null || orderNo == null) return 0L;
        PaymentAttempt a = attemptMapper.selectOne(new LambdaQueryWrapper<PaymentAttempt>()
                .eq(PaymentAttempt::getOrderNo, orderNo).orderByDesc(PaymentAttempt::getId).last("LIMIT 1"));
        return a != null && a.getId() != null ? a.getId() : 0L;
    }

    private long getMaxRefundId(String orderNo) {
        if (refundMapper == null || orderNo == null) return 0L;
        PaymentRefundOrder r = refundMapper.selectOne(new LambdaQueryWrapper<PaymentRefundOrder>()
                .eq(PaymentRefundOrder::getOrderNo, orderNo).orderByDesc(PaymentRefundOrder::getId).last("LIMIT 1"));
        return r != null && r.getId() != null ? r.getId() : 0L;
    }

    private long getMaxEventId(String orderNo) {
        if (financialEventMapper == null || orderNo == null) return 0L;
        PaymentFinancialEvent e = financialEventMapper.selectOne(new LambdaQueryWrapper<PaymentFinancialEvent>()
                .eq(PaymentFinancialEvent::getOrderNo, orderNo).orderByDesc(PaymentFinancialEvent::getId).last("LIMIT 1"));
        return e != null && e.getId() != null ? e.getId() : 0L;
    }

    private long getMaxDiffId(String orderNo) {
        if (diffMapper == null || orderNo == null) return 0L;
        ReconDiff d = diffMapper.selectOne(new LambdaQueryWrapper<ReconDiff>()
                .eq(ReconDiff::getOrderNo, orderNo).orderByDesc(ReconDiff::getId).last("LIMIT 1"));
        return d != null && d.getId() != null ? d.getId() : 0L;
    }

    private static CursorPayload parseCursor(String cursor, String expectedScopeKey) {
        if (cursor == null || cursor.isBlank()) return null;
        if (!cursor.startsWith("CUR_V1:")) {
            if (cursor.startsWith("CUR:")) {
                CursorPayload p = new CursorPayload();
                String[] parts = cursor.substring(4).split(":");
                if (parts.length >= 1 && !parts[0].isBlank()) p.attemptAfterId = Long.parseLong(parts[0]);
                if (parts.length >= 2 && !parts[1].isBlank()) p.refundAfterId = Long.parseLong(parts[1]);
                if (parts.length >= 3 && !parts[2].isBlank()) p.eventAfterId = Long.parseLong(parts[2]);
                if (parts.length >= 4 && !parts[3].isBlank()) p.diffAfterId = Long.parseLong(parts[3]);
                p.scopeKey = expectedScopeKey;
                p.expiresAt = System.currentTimeMillis() + 600000;
                return p;
            }
            throw new IllegalArgumentException("快照游标格式非法");
        }
        try {
            String encoded = cursor.substring("CUR_V1:".length());
            int dotIdx = encoded.lastIndexOf('.');
            if (dotIdx <= 0) {
                throw new IllegalArgumentException("快照游标缺少签名");
            }
            String jsonBase64 = encoded.substring(0, dotIdx);
            String sig = encoded.substring(dotIdx + 1);
            String expectedSig = MoneySecuritySupport.computeFingerprint("MALL_CURSOR_SALT:" + jsonBase64);
            if (!sig.equals(expectedSig)) {
                throw new IllegalArgumentException("快照游标签名无效或已被篡改");
            }
            byte[] bytes = Base64.getUrlDecoder().decode(jsonBase64);
            CursorPayload payload = OBJECT_MAPPER.readValue(bytes, CursorPayload.class);
            if (payload.expiresAt < System.currentTimeMillis()) {
                throw new IllegalArgumentException("快照游标已过期");
            }
            if (expectedScopeKey != null && payload.scopeKey != null && !payload.scopeKey.equals(expectedScopeKey)) {
                throw new IllegalArgumentException("快照游标作用域不匹配");
            }
            return payload;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("快照游标解析失败: " + e.getMessage(), e);
        }
    }

    private static String buildSignedCursor(CursorPayload payload) {
        try {
            byte[] bytes = OBJECT_MAPPER.writeValueAsBytes(payload);
            String jsonBase64 = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            String sig = MoneySecuritySupport.computeFingerprint("MALL_CURSOR_SALT:" + jsonBase64);
            return "CUR_V1:" + jsonBase64 + "." + sig;
        } catch (Exception e) {
            throw new RuntimeException("生成快照游标失败", e);
        }
    }

    private static String formatAmount(BigDecimal amount) {
        if (amount == null) return null;
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String buildCanonicalFactsSummary(String orderNo,
                                                     PaymentOrder paymentOrder,
                                                     List<PaymentAttemptSnapshot> attempts,
                                                     List<PaymentRefundOrderSnapshot> refunds,
                                                     List<PaymentFinancialEventSnapshot> events,
                                                     TradeOrchestrationSnapshot orch,
                                                     List<ReconDiffSummarySnapshot> diffs,
                                                     CoverageInfo coverage) {
        Map<String, Object> canonical = new TreeMap<>();
        canonical.put("schemaVersion", 1);
        canonical.put("orderNo", orderNo != null ? orderNo : "");

        if (paymentOrder != null) {
            Map<String, Object> pm = new TreeMap<>();
            pm.put("paymentNo", paymentOrder.getPaymentNo() != null ? paymentOrder.getPaymentNo() : "");
            pm.put("status", paymentOrder.getStatus() != null ? paymentOrder.getStatus() : "");
            pm.put("amount", formatAmount(paymentOrder.getAmount()));
            pm.put("version", paymentOrder.getVersion() != null ? paymentOrder.getVersion() : 0L);
            canonical.put("paymentOrder", pm);
        } else {
            canonical.put("paymentOrder", null);
        }

        List<Map<String, Object>> attList = new ArrayList<>();
        if (attempts != null) {
            for (PaymentAttemptSnapshot a : attempts) {
                Map<String, Object> m = new TreeMap<>();
                m.put("attemptNo", a.getAttemptNo() != null ? a.getAttemptNo() : "");
                m.put("status", a.getStatus() != null ? a.getStatus() : "");
                m.put("amount", formatAmount(a.getAmount()));
                m.put("currency", a.getCurrency() != null ? a.getCurrency() : "");
                m.put("allocationStatus", a.getAllocationStatus() != null ? a.getAllocationStatus() : "");
                m.put("version", a.getVersion() != null ? a.getVersion() : 0L);
                m.put("channelSuccessAt", a.getChannelSuccessAt() != null ? a.getChannelSuccessAt() : "");
                attList.add(m);
            }
        }
        attList.sort(Comparator.comparing(m -> String.valueOf(m.get("attemptNo"))));
        canonical.put("attempts", attList);

        List<Map<String, Object>> refList = new ArrayList<>();
        if (refunds != null) {
            for (PaymentRefundOrderSnapshot r : refunds) {
                Map<String, Object> m = new TreeMap<>();
                m.put("refundNo", r.getRefundNo() != null ? r.getRefundNo() : "");
                m.put("status", r.getStatus() != null ? r.getStatus() : "");
                m.put("amount", formatAmount(r.getAmount()));
                m.put("currency", r.getCurrency() != null ? r.getCurrency() : "");
                m.put("channelRefundNo", r.getChannelRefundNo() != null ? r.getChannelRefundNo() : "");
                m.put("version", r.getVersion() != null ? r.getVersion() : 0L);
                m.put("successAt", r.getSuccessAt() != null ? r.getSuccessAt() : "");
                refList.add(m);
            }
        }
        refList.sort(Comparator.comparing(m -> String.valueOf(m.get("refundNo"))));
        canonical.put("refunds", refList);

        List<Map<String, Object>> evtList = new ArrayList<>();
        if (events != null) {
            for (PaymentFinancialEventSnapshot e : events) {
                Map<String, Object> m = new TreeMap<>();
                m.put("eventNo", e.getEventNo() != null ? e.getEventNo() : "");
                m.put("eventType", e.getEventType() != null ? e.getEventType() : "");
                m.put("eventStage", e.getEventStage() != null ? e.getEventStage() : "");
                m.put("amount", formatAmount(e.getAmount()));
                m.put("occurredAt", e.getOccurredAt() != null ? e.getOccurredAt() : "");
                evtList.add(m);
            }
        }
        evtList.sort(Comparator.comparing(m -> String.valueOf(m.get("eventNo"))));
        canonical.put("events", evtList);

        if (orch != null) {
            Map<String, Object> o = new TreeMap<>();
            o.put("taskNo", orch.getTaskNo() != null ? orch.getTaskNo() : "");
            o.put("status", orch.getStatus() != null ? orch.getStatus() : "");
            o.put("currentStep", orch.getCurrentStep() != null ? orch.getCurrentStep() : "");
            canonical.put("orchestration", o);
        } else {
            canonical.put("orchestration", null);
        }

        List<Map<String, Object>> diffList = new ArrayList<>();
        if (diffs != null) {
            for (ReconDiffSummarySnapshot d : diffs) {
                Map<String, Object> m = new TreeMap<>();
                m.put("diffNo", d.getDiffNo() != null ? d.getDiffNo() : "");
                m.put("diffType", d.getDiffType() != null ? d.getDiffType() : "");
                m.put("status", d.getStatus() != null ? d.getStatus() : "");
                m.put("channelAmount", formatAmount(d.getChannelAmount()));
                m.put("localAmount", formatAmount(d.getLocalAmount()));
                diffList.add(m);
            }
        }
        diffList.sort(Comparator.comparing(m -> String.valueOf(m.get("diffNo"))));
        canonical.put("diffs", diffList);

        if (coverage != null) {
            Map<String, Object> cov = new TreeMap<>();
            cov.put("isComplete", coverage.isComplete());
            cov.put("isHasMore", coverage.isHasMore());
            cov.put("sources", coverage.getSources() != null ? new TreeMap<>(coverage.getSources()) : Collections.emptyMap());
            canonical.put("coverage", cov);
        } else {
            canonical.put("coverage", null);
        }

        try {
            return OBJECT_MAPPER.writeValueAsString(canonical);
        } catch (Exception e) {
            return "{\"orderNo\":\"" + orderNo + "\"}";
        }
    }
}
