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

        // 解析游标
        CursorTokens cursorTokens = parseCursor(query.getCursor());

        PaymentOrder paymentOrder = orderNo != null
                ? paymentOrderMapper.selectOne(new LambdaQueryWrapper<PaymentOrder>().eq(PaymentOrder::getOrderNo, orderNo))
                : null;

        // 1. 查询支付尝试 (Limit + 1 探测截断)
        List<PaymentAttempt> rawAttempts = orderNo != null
                ? attemptMapper.selectList(new LambdaQueryWrapper<PaymentAttempt>()
                .eq(PaymentAttempt::getOrderNo, orderNo)
                .gt(cursorTokens.attemptId > 0, PaymentAttempt::getId, cursorTokens.attemptId)
                .orderByAsc(PaymentAttempt::getId)
                .last("LIMIT " + (pageSize + 1)))
                : Collections.emptyList();
        boolean attemptsTruncated = rawAttempts != null && rawAttempts.size() > pageSize;
        List<PaymentAttempt> attempts = attemptsTruncated ? rawAttempts.subList(0, pageSize) : rawAttempts;
        long lastAttemptId = (attempts != null && !attempts.isEmpty()) ? attempts.get(attempts.size() - 1).getId() : cursorTokens.attemptId;

        // 2. 查询退款单 (Limit + 1 探测截断)
        List<PaymentRefundOrder> rawRefunds = orderNo != null
                ? refundMapper.selectList(new LambdaQueryWrapper<PaymentRefundOrder>()
                .eq(PaymentRefundOrder::getOrderNo, orderNo)
                .gt(cursorTokens.refundId > 0, PaymentRefundOrder::getId, cursorTokens.refundId)
                .orderByAsc(PaymentRefundOrder::getId)
                .last("LIMIT " + (pageSize + 1)))
                : Collections.emptyList();
        boolean refundsTruncated = rawRefunds != null && rawRefunds.size() > pageSize;
        List<PaymentRefundOrder> refunds = refundsTruncated ? rawRefunds.subList(0, pageSize) : rawRefunds;
        long lastRefundId = (refunds != null && !refunds.isEmpty()) ? refunds.get(refunds.size() - 1).getId() : cursorTokens.refundId;

        // 3. 查询金融事件 (Limit + 1 探测截断)
        List<PaymentFinancialEvent> rawEvents = orderNo != null
                ? financialEventMapper.selectList(new LambdaQueryWrapper<PaymentFinancialEvent>()
                .eq(PaymentFinancialEvent::getOrderNo, orderNo)
                .gt(cursorTokens.eventId > 0, PaymentFinancialEvent::getId, cursorTokens.eventId)
                .orderByAsc(PaymentFinancialEvent::getId)
                .last("LIMIT " + (pageSize + 1)))
                : Collections.emptyList();
        boolean eventsTruncated = rawEvents != null && rawEvents.size() > pageSize;
        List<PaymentFinancialEvent> events = eventsTruncated ? rawEvents.subList(0, pageSize) : rawEvents;
        long lastEventId = (events != null && !events.isEmpty()) ? events.get(events.size() - 1).getId() : cursorTokens.eventId;

        // 4. 查询编排任务
        TradeOrchestrationTask orchTask = orderNo != null
                ? orchestrationMapper.selectOne(new LambdaQueryWrapper<TradeOrchestrationTask>()
                .eq(TradeOrchestrationTask::getBizNo, orderNo)
                .orderByDesc(TradeOrchestrationTask::getId)
                .last("LIMIT 1"))
                : null;

        // 5. 查询对账差异 (Limit + 1 探测截断)
        List<ReconDiff> rawDiffs = orderNo != null
                ? diffMapper.selectList(new LambdaQueryWrapper<ReconDiff>()
                .eq(ReconDiff::getOrderNo, orderNo)
                .gt(cursorTokens.diffId > 0, ReconDiff::getId, cursorTokens.diffId)
                .orderByAsc(ReconDiff::getId)
                .last("LIMIT " + (pageSize + 1)))
                : Collections.emptyList();
        boolean diffsTruncated = rawDiffs != null && rawDiffs.size() > pageSize;
        List<ReconDiff> diffs = diffsTruncated ? rawDiffs.subList(0, pageSize) : rawDiffs;
        long lastDiffId = (diffs != null && !diffs.isEmpty()) ? diffs.get(diffs.size() - 1).getId() : cursorTokens.diffId;

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

        boolean hasMore = attemptsTruncated || refundsTruncated || eventsTruncated || diffsTruncated;
        String nextCursor = hasMore ? buildCursor(lastAttemptId, lastRefundId, lastEventId, lastDiffId) : null;

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

    private static class CursorTokens {
        long attemptId = 0L;
        long refundId = 0L;
        long eventId = 0L;
        long diffId = 0L;
    }

    private static CursorTokens parseCursor(String cursor) {
        CursorTokens tokens = new CursorTokens();
        if (cursor == null || !cursor.startsWith("CUR:")) return tokens;
        try {
            String[] parts = cursor.substring(4).split(":");
            if (parts.length >= 1 && !parts[0].isBlank()) tokens.attemptId = Long.parseLong(parts[0]);
            if (parts.length >= 2 && !parts[1].isBlank()) tokens.refundId = Long.parseLong(parts[1]);
            if (parts.length >= 3 && !parts[2].isBlank()) tokens.eventId = Long.parseLong(parts[2]);
            if (parts.length >= 4 && !parts[3].isBlank()) tokens.diffId = Long.parseLong(parts[3]);
        } catch (Exception ignored) {}
        return tokens;
    }

    private static String buildCursor(long lastAttemptId, long lastRefundId, long lastEventId, long lastDiffId) {
        return "CUR:" + lastAttemptId + ":" + lastRefundId + ":" + lastEventId + ":" + lastDiffId;
    }

    private static String buildCanonicalFactsSummary(String orderNo,
                                                    PaymentOrder paymentOrder,
                                                    List<PaymentAttemptSnapshot> attempts,
                                                    List<PaymentRefundOrderSnapshot> refunds,
                                                    List<PaymentFinancialEventSnapshot> events,
                                                    TradeOrchestrationSnapshot orch,
                                                    List<ReconDiffSummarySnapshot> diffs,
                                                    CoverageInfo coverage) {
        StringBuilder sb = new StringBuilder();
        sb.append("ORDER=").append(orderNo != null ? orderNo : "null").append(";");
        if (paymentOrder != null) {
            sb.append("PM=").append(paymentOrder.getPaymentNo()).append("|")
                    .append(paymentOrder.getStatus()).append("|")
                    .append(paymentOrder.getAmount()).append("|")
                    .append(paymentOrder.getVersion()).append(";");
        }

        sb.append("ATTEMPTS=[");
        if (attempts != null) {
            for (PaymentAttemptSnapshot a : attempts) {
                sb.append(a.getAttemptNo()).append("|")
                        .append(a.getStatus()).append("|")
                        .append(a.getAmount()).append("|")
                        .append(a.getCurrency()).append("|")
                        .append(a.getAllocationStatus()).append("|")
                        .append(a.getVersion()).append("|")
                        .append(a.getChannelSuccessAt()).append(",");
            }
        }
        sb.append("];");

        sb.append("REFUNDS=[");
        if (refunds != null) {
            for (PaymentRefundOrderSnapshot r : refunds) {
                sb.append(r.getRefundNo()).append("|")
                        .append(r.getStatus()).append("|")
                        .append(r.getAmount()).append("|")
                        .append(r.getCurrency()).append("|")
                        .append(r.getChannelRefundNo()).append("|")
                        .append(r.getVersion()).append("|")
                        .append(r.getSuccessAt()).append(",");
            }
        }
        sb.append("];");

        sb.append("EVENTS=[");
        if (events != null) {
            for (PaymentFinancialEventSnapshot e : events) {
                sb.append(e.getEventNo()).append("|")
                        .append(e.getEventType()).append("|")
                        .append(e.getEventStage()).append("|")
                        .append(e.getAmount()).append("|")
                        .append(e.getOccurredAt()).append(",");
            }
        }
        sb.append("];");

        if (orch != null) {
            sb.append("ORCH=").append(orch.getTaskNo()).append("|")
                    .append(orch.getStatus()).append("|")
                    .append(orch.getCurrentStep()).append(";");
        }

        sb.append("DIFFS=[");
        if (diffs != null) {
            for (ReconDiffSummarySnapshot d : diffs) {
                sb.append(d.getDiffNo()).append("|")
                        .append(d.getDiffType()).append("|")
                        .append(d.getStatus()).append("|")
                        .append(d.getChannelAmount()).append("|")
                        .append(d.getLocalAmount()).append(",");
            }
        }
        sb.append("];");

        if (coverage != null) {
            sb.append("COV=").append(coverage.isComplete()).append("|")
                    .append(coverage.isHasMore()).append("|")
                    .append(coverage.getSources());
        }

        return sb.toString();
    }
}
