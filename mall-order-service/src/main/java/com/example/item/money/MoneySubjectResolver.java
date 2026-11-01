package com.example.item.money;

import api.agent.money.*;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.item.entity.TradeOrder;
import com.example.item.mapper.TradeOrderMapper;
import com.example.item.payment.domain.PaymentAttempt;
import com.example.item.payment.domain.PaymentRefundOrder;
import com.example.item.payment.mapper.PaymentAttemptMapper;
import com.example.item.payment.mapper.PaymentRefundOrderMapper;
import com.example.item.reconciliation.domain.ReconDiff;
import com.example.item.reconciliation.mapper.ReconDiffMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

@Component
public class MoneySubjectResolver {

    private final TradeOrderMapper orderMapper;
    private final PaymentAttemptMapper attemptMapper;
    private final PaymentRefundOrderMapper refundMapper;
    private final ReconDiffMapper diffMapper;

    public MoneySubjectResolver(TradeOrderMapper orderMapper,
                                PaymentAttemptMapper attemptMapper,
                                PaymentRefundOrderMapper refundMapper,
                                ReconDiffMapper diffMapper) {
        this.orderMapper = orderMapper;
        this.attemptMapper = attemptMapper;
        this.refundMapper = refundMapper;
        this.diffMapper = diffMapper;
    }

    public ResolveMoneySubjectResult resolve(MoneyAccessContext access, MoneySubjectSelector selector) {
        if (access == null) {
            return ResolveMoneySubjectResult.fail(MoneyErrorCodes.MONEY_FORBIDDEN, "访问凭据 (access) 缺失，拒绝解析");
        }

        if (selector == null) {
            return ResolveMoneySubjectResult.fail(MoneyErrorCodes.MONEY_SUBJECT_NOT_FOUND, "未指定调查目标");
        }

        String initialScope = selector.getOrderNo() != null && !selector.getOrderNo().isBlank()
                ? "ORDER:" + selector.getOrderNo().trim() : null;

        MoneySecuritySupport.VerifiedAccess verified;
        try {
            verified = MoneySecuritySupport.verifyEntryAccess(access, "mall-order-service", "RESOLVE_SUBJECT", initialScope);
        } catch (Exception e) {
            return ResolveMoneySubjectResult.fail(MoneyErrorCodes.MONEY_FORBIDDEN, "凭据校验未通过: " + e.getMessage());
        }

        // 1. 如果指定了 attemptNo，查询并校验
        PaymentAttempt attempt = null;
        if (selector.getAttemptNo() != null && !selector.getAttemptNo().isBlank()) {
            attempt = attemptMapper.selectOne(new LambdaQueryWrapper<PaymentAttempt>()
                    .eq(PaymentAttempt::getAttemptNo, selector.getAttemptNo().trim()));
            if (attempt == null) {
                return ResolveMoneySubjectResult.fail(MoneyErrorCodes.MONEY_SUBJECT_NOT_FOUND, "指定的支付尝试不存在: " + selector.getAttemptNo());
            }
        }

        // 2. 如果指定了 refundNo，查询并校验
        PaymentRefundOrder refund = null;
        if (selector.getRefundNo() != null && !selector.getRefundNo().isBlank()) {
            refund = refundMapper.selectOne(new LambdaQueryWrapper<PaymentRefundOrder>()
                    .eq(PaymentRefundOrder::getRefundNo, selector.getRefundNo().trim()));
            if (refund == null) {
                return ResolveMoneySubjectResult.fail(MoneyErrorCodes.MONEY_SUBJECT_NOT_FOUND, "指定的退款单不存在: " + selector.getRefundNo());
            }
        }

        // 3. 如果指定了 diffNo，查询并校验
        ReconDiff diff = null;
        if (selector.getDiffNo() != null && !selector.getDiffNo().isBlank()) {
            diff = diffMapper.selectOne(new LambdaQueryWrapper<ReconDiff>()
                    .eq(ReconDiff::getDiffNo, selector.getDiffNo().trim()));
            if (diff == null) {
                return ResolveMoneySubjectResult.fail(MoneyErrorCodes.MONEY_SUBJECT_NOT_FOUND, "指定的对账差异不存在: " + selector.getDiffNo());
            }
        }

        // 4. 多编号关联性一致性校验 (Multi-ID Correlation Check)
        String targetOrderNo = selector.getOrderNo() != null && !selector.getOrderNo().isBlank() ? selector.getOrderNo().trim() : null;

        if (attempt != null) {
            if (targetOrderNo != null && !targetOrderNo.equals(attempt.getOrderNo())) {
                return ResolveMoneySubjectResult.fail(MoneyErrorCodes.MONEY_SUBJECT_MISMATCH,
                        "支付尝试 " + attempt.getAttemptNo() + " 与目标订单 " + targetOrderNo + " 不匹配");
            }
            if (targetOrderNo == null) {
                targetOrderNo = attempt.getOrderNo();
            }
        }

        if (refund != null) {
            if (targetOrderNo != null && !targetOrderNo.equals(refund.getOrderNo())) {
                return ResolveMoneySubjectResult.fail(MoneyErrorCodes.MONEY_SUBJECT_MISMATCH,
                        "退款单 " + refund.getRefundNo() + " 与目标订单 " + targetOrderNo + " 不匹配");
            }
            if (targetOrderNo == null) {
                targetOrderNo = refund.getOrderNo();
            }
        }

        if (diff != null) {
            String diffOrderNo = diff.getOrderNo();
            if ((diffOrderNo == null || diffOrderNo.isBlank()) && diff.getAttemptNo() != null && !diff.getAttemptNo().isBlank()) {
                PaymentAttempt diffAtt = attemptMapper.selectOne(new LambdaQueryWrapper<PaymentAttempt>()
                        .eq(PaymentAttempt::getAttemptNo, diff.getAttemptNo()));
                if (diffAtt != null) diffOrderNo = diffAtt.getOrderNo();
            }
            if ((diffOrderNo == null || diffOrderNo.isBlank()) && diff.getRefundNo() != null && !diff.getRefundNo().isBlank()) {
                PaymentRefundOrder diffRef = refundMapper.selectOne(new LambdaQueryWrapper<PaymentRefundOrder>()
                        .eq(PaymentRefundOrder::getRefundNo, diff.getRefundNo()));
                if (diffRef != null) diffOrderNo = diffRef.getOrderNo();
            }

            if (diffOrderNo != null && !diffOrderNo.isBlank()) {
                if (targetOrderNo != null && !targetOrderNo.equals(diffOrderNo)) {
                    return ResolveMoneySubjectResult.fail(MoneyErrorCodes.MONEY_SUBJECT_MISMATCH,
                            "对账差异 " + diff.getDiffNo() + " 与目标订单 " + targetOrderNo + " 不匹配");
                }
                if (targetOrderNo == null) {
                    targetOrderNo = diffOrderNo;
                }
            } else {
                // diff 完全未关联订单
                if (targetOrderNo != null) {
                    return ResolveMoneySubjectResult.fail(MoneyErrorCodes.MONEY_SUBJECT_MISMATCH,
                            "对账差异 " + diff.getDiffNo() + " 无法关联至目标订单 " + targetOrderNo);
                }
            }
        }

        // 5. 若无法关联订单，检查是否为独立的 RECON 对账差异分支
        if (targetOrderNo == null || targetOrderNo.isBlank()) {
            if (diff != null) {
                if (!isFinanceRole(verified.getActorRole())) {
                    return ResolveMoneySubjectResult.fail(MoneyErrorCodes.MONEY_FORBIDDEN, "仅财务人员获准调查未关联订单的对账差异");
                }
                String profileId = diff.getProfileId() != null ? diff.getProfileId() : "DEFAULT";
                String scopeKey = "RECON:" + profileId + ":" + diff.getDiffNo();
                if (verified.getScopeKey() != null && !verified.getScopeKey().isBlank()) {
                    if (!verified.getScopeKey().equals(scopeKey) && !verified.getScopeKey().equals(diff.getDiffNo())) {
                        return ResolveMoneySubjectResult.fail(MoneyErrorCodes.MONEY_FORBIDDEN,
                                "委托授权范围 (" + verified.getScopeKey() + ") 与对账差异 (" + scopeKey + ") 不匹配");
                    }
                }
                String fingerprint = MoneySecuritySupport.computeFingerprint(scopeKey);
                List<String> attempts = diff.getAttemptNo() != null ? List.of(diff.getAttemptNo()) : Collections.emptyList();
                List<String> refunds = diff.getRefundNo() != null ? List.of(diff.getRefundNo()) : Collections.emptyList();
                String scopeProof = MoneySecuritySupport.createScopeProof(
                        null,
                        scopeKey,
                        null,
                        null,
                        verified.getGrantId(),
                        List.of("READ", "QUERY_FUND"),
                        attempts,
                        refunds,
                        fingerprint,
                        "mall-user-service,mall-money-services"
                );
                return new ResolveMoneySubjectResult(
                        null,
                        null,
                        null,
                        "FINANCE_VIEW",
                        attempts,
                        refunds,
                        scopeProof,
                        fingerprint,
                        "SUCCESS",
                        null
                );
            }
            return ResolveMoneySubjectResult.fail(MoneyErrorCodes.MONEY_SUBJECT_NOT_FOUND, "无法解析出有效的调查对象");
        }

        // 6. 校验委托 scopeKey 与目标订单是否一致
        String expectedOrderScope = "ORDER:" + targetOrderNo;
        if (verified.getScopeKey() != null && !verified.getScopeKey().isBlank()) {
            if (!verified.getScopeKey().equals(expectedOrderScope) && !verified.getScopeKey().equals(targetOrderNo)) {
                return ResolveMoneySubjectResult.fail(MoneyErrorCodes.MONEY_FORBIDDEN,
                        "委托授权范围 (" + verified.getScopeKey() + ") 与目标订单 (" + expectedOrderScope + ") 不匹配");
            }
        }

        // 7. 校验订单存在性与归属权限
        TradeOrder order = orderMapper.selectOne(new LambdaQueryWrapper<TradeOrder>()
                .eq(TradeOrder::getOrderNo, targetOrderNo.trim()));
        if (order == null) {
            return ResolveMoneySubjectResult.fail(MoneyErrorCodes.MONEY_SUBJECT_NOT_FOUND, "订单不存在: " + targetOrderNo);
        }

        String verifiedRole = verified.getActorRole();
        Long verifiedUserId = verified.getActorUserId();
        String allowedView;

        if (isFinanceRole(verifiedRole)) {
            allowedView = "FINANCE_VIEW";
        } else if ("CUSTOMER_STAFF".equalsIgnoreCase(verifiedRole)) {
            // 客服坐席限定已分配会话买家主体，仅赋予 BUYER_VIEW，不具有全平台财务视图
            if (verifiedUserId != null && !Objects.equals(order.getBuyerId(), verifiedUserId)) {
                return ResolveMoneySubjectResult.fail(MoneyErrorCodes.MONEY_FORBIDDEN, "客服坐席主体与当前订单买家不匹配");
            }
            allowedView = "BUYER_VIEW";
        } else if ("SELLER".equalsIgnoreCase(verifiedRole)) {
            if (verifiedUserId == null || !Objects.equals(order.getSellerId(), verifiedUserId)) {
                return ResolveMoneySubjectResult.fail(MoneyErrorCodes.MONEY_FORBIDDEN, "无权查看该订单的资金信息（商家主体不匹配）");
            }
            allowedView = "SELLER_VIEW";
        } else if ("BUYER".equalsIgnoreCase(verifiedRole)) {
            if (verifiedUserId == null || !Objects.equals(order.getBuyerId(), verifiedUserId)) {
                return ResolveMoneySubjectResult.fail(MoneyErrorCodes.MONEY_FORBIDDEN, "无权查看该订单的资金信息（买家主体不匹配）");
            }
            allowedView = "BUYER_VIEW";
        } else {
            return ResolveMoneySubjectResult.fail(MoneyErrorCodes.MONEY_FORBIDDEN, "未授权的访问角色: " + verifiedRole);
        }

        // 8. 收集受验证的支付尝试列表 (PA_ 开头)
        List<PaymentAttempt> attempts = attemptMapper.selectList(new LambdaQueryWrapper<PaymentAttempt>()
                .eq(PaymentAttempt::getOrderNo, targetOrderNo)
                .orderByAsc(PaymentAttempt::getAttemptSeq));
        List<String> verifiedAttempts = new ArrayList<>();
        if (attempts != null) {
            attempts.forEach(a -> {
                if (a.getAttemptNo() != null && !a.getAttemptNo().isBlank()) {
                    verifiedAttempts.add(a.getAttemptNo());
                }
            });
        }

        // 9. 收集受验证的退款单列表 (RF_ 开头)
        List<PaymentRefundOrder> refunds = refundMapper.selectList(new LambdaQueryWrapper<PaymentRefundOrder>()
                .eq(PaymentRefundOrder::getOrderNo, targetOrderNo)
                .orderByAsc(PaymentRefundOrder::getId));
        List<String> verifiedRefunds = new ArrayList<>();
        if (refunds != null) {
            refunds.forEach(r -> {
                if (r.getRefundNo() != null && !r.getRefundNo().isBlank()) {
                    verifiedRefunds.add(r.getRefundNo());
                }
            });
        }

        // 10. 计算规范指纹并签发范围证明 (Scope Proof，首批最多 50 个引用)
        String rawData = targetOrderNo + ":" + order.getBuyerId() + ":" + order.getSellerId() + ":"
                + verifiedAttempts.size() + ":" + verifiedRefunds.size();
        String scopeFingerprint = MoneySecuritySupport.computeFingerprint(rawData);

        List<String> proofAttempts = verifiedAttempts;
        List<String> proofRefunds = verifiedRefunds;
        if (verifiedAttempts.size() + verifiedRefunds.size() > 50) {
            int maxAtt = Math.min(verifiedAttempts.size(), 50);
            proofAttempts = verifiedAttempts.subList(0, maxAtt);
            int remain = 50 - proofAttempts.size();
            int maxRef = Math.min(verifiedRefunds.size(), remain);
            proofRefunds = verifiedRefunds.subList(0, maxRef);
        }

        String scopeProof = MoneySecuritySupport.createScopeProof(
                targetOrderNo,
                expectedOrderScope,
                order.getBuyerId(),
                order.getSellerId(),
                verified.getGrantId(),
                List.of("READ", "QUERY_FUND"),
                proofAttempts,
                proofRefunds,
                scopeFingerprint,
                "mall-user-service,mall-money-services"
        );

        String buyerRef = "BUYER:" + order.getBuyerId();
        String sellerRef = "SELLER:" + order.getSellerId();

        return new ResolveMoneySubjectResult(
                targetOrderNo,
                buyerRef,
                sellerRef,
                allowedView,
                verifiedAttempts,
                verifiedRefunds,
                scopeProof,
                scopeFingerprint,
                "SUCCESS",
                null
        );
    }

    private boolean isFinanceRole(String role) {
        if (role == null) return false;
        String r = role.toUpperCase();
        return "ADMIN".equals(r) || "FINANCE".equals(r) || "SYSTEM".equals(r);
    }
}
