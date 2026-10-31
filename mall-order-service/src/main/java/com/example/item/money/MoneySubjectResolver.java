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

        MoneySecuritySupport.VerifiedAccess verified;
        try {
            verified = MoneySecuritySupport.verifyEntryAccess(access, "mall-order-service", "RESOLVE_SUBJECT", null);
        } catch (Exception e) {
            return ResolveMoneySubjectResult.fail(MoneyErrorCodes.MONEY_FORBIDDEN, "凭据校验未通过: " + e.getMessage());
        }

        if (selector == null) {
            return ResolveMoneySubjectResult.fail(MoneyErrorCodes.MONEY_SUBJECT_NOT_FOUND, "未指定调查目标");
        }

        String targetOrderNo = selector.getOrderNo();

        // 1. 如果通过 attemptNo 查找
        if ((targetOrderNo == null || targetOrderNo.isBlank()) && selector.getAttemptNo() != null && !selector.getAttemptNo().isBlank()) {
            PaymentAttempt attempt = attemptMapper.selectOne(new LambdaQueryWrapper<PaymentAttempt>()
                    .eq(PaymentAttempt::getAttemptNo, selector.getAttemptNo().trim()));
            if (attempt != null) {
                targetOrderNo = attempt.getOrderNo();
            }
        }

        // 2. 如果通过 refundNo 查找
        if ((targetOrderNo == null || targetOrderNo.isBlank()) && selector.getRefundNo() != null && !selector.getRefundNo().isBlank()) {
            PaymentRefundOrder refund = refundMapper.selectOne(new LambdaQueryWrapper<PaymentRefundOrder>()
                    .eq(PaymentRefundOrder::getRefundNo, selector.getRefundNo().trim()));
            if (refund != null) {
                targetOrderNo = refund.getOrderNo();
            }
        }

        // 3. 如果通过 diffNo 查找
        if ((targetOrderNo == null || targetOrderNo.isBlank()) && selector.getDiffNo() != null && !selector.getDiffNo().isBlank()) {
            ReconDiff diff = diffMapper.selectOne(new LambdaQueryWrapper<ReconDiff>()
                    .eq(ReconDiff::getDiffNo, selector.getDiffNo().trim()));
            if (diff != null) {
                if (diff.getOrderNo() != null && !diff.getOrderNo().isBlank()) {
                    targetOrderNo = diff.getOrderNo();
                } else if (diff.getAttemptNo() != null && !diff.getAttemptNo().isBlank()) {
                    PaymentAttempt attempt = attemptMapper.selectOne(new LambdaQueryWrapper<PaymentAttempt>()
                            .eq(PaymentAttempt::getAttemptNo, diff.getAttemptNo()));
                    if (attempt != null) targetOrderNo = attempt.getOrderNo();
                } else if (diff.getRefundNo() != null && !diff.getRefundNo().isBlank()) {
                    PaymentRefundOrder refund = refundMapper.selectOne(new LambdaQueryWrapper<PaymentRefundOrder>()
                            .eq(PaymentRefundOrder::getRefundNo, diff.getRefundNo()));
                    if (refund != null) targetOrderNo = refund.getOrderNo();
                }

                // 若对账单边差异完全无法关联订单，在财务/管理权限下允许以 RECON 作用域独立建案
                if (targetOrderNo == null || targetOrderNo.isBlank()) {
                    if (isStaffOrFinance(verified.getActorRole())) {
                        String scopeKey = "RECON:" + (diff.getProfileId() != null ? diff.getProfileId() : "DEFAULT") + ":" + diff.getDiffNo();
                        String fingerprint = MoneySecuritySupport.computeFingerprint(scopeKey);
                        String scopeProof = MoneySecuritySupport.createScopeProof(null, null, null, List.of(), List.of(), fingerprint, "mall-money-services");
                        return new ResolveMoneySubjectResult(null, null, null, "FINANCE_VIEW",
                                List.of(), List.of(), scopeProof, fingerprint, "SUCCESS", null);
                    } else {
                        return ResolveMoneySubjectResult.fail(MoneyErrorCodes.MONEY_FORBIDDEN, "仅财务人员可调查未关联订单的对账差异");
                    }
                }
            }
        }

        if (targetOrderNo == null || targetOrderNo.isBlank()) {
            return ResolveMoneySubjectResult.fail(MoneyErrorCodes.MONEY_SUBJECT_NOT_FOUND, "无法解析出有效的订单对象");
        }

        // 4. 校验订单存在性与归属权限
        TradeOrder order = orderMapper.selectOne(new LambdaQueryWrapper<TradeOrder>()
                .eq(TradeOrder::getOrderNo, targetOrderNo.trim()));
        if (order == null) {
            return ResolveMoneySubjectResult.fail(MoneyErrorCodes.MONEY_SUBJECT_NOT_FOUND, "订单不存在: " + targetOrderNo);
        }

        String verifiedRole = verified.getActorRole();
        Long verifiedUserId = verified.getActorUserId();
        String allowedView;

        if (isStaffOrFinance(verifiedRole)) {
            allowedView = "FINANCE_VIEW";
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

        // 5. 收集受验证的支付尝试列表 (PA_ 开头)
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

        // 6. 收集受验证的退款单列表 (RF_ 开头)
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

        // 7. 计算规范指纹并签发范围证明 (Scope Proof)
        String rawData = targetOrderNo + ":" + order.getBuyerId() + ":" + order.getSellerId() + ":"
                + verifiedAttempts.size() + ":" + verifiedRefunds.size();
        String scopeFingerprint = MoneySecuritySupport.computeFingerprint(rawData);

        String scopeProof = MoneySecuritySupport.createScopeProof(
                targetOrderNo,
                order.getBuyerId(),
                order.getSellerId(),
                verifiedAttempts,
                verifiedRefunds,
                scopeFingerprint,
                "mall-money-services"
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

    private boolean isStaffOrFinance(String role) {
        if (role == null) return false;
        String r = role.toUpperCase();
        return "ADMIN".equals(r) || "FINANCE".equals(r) || "CUSTOMER_STAFF".equals(r) || "SYSTEM".equals(r);
    }
}
