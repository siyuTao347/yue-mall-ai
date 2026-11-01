package com.example.user.provider;

import api.agent.money.*;
import api.exception.ForbiddenException;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.user.entity.FundBusinessRef;
import com.example.user.entity.FundFlow;
import com.example.user.entity.FundTransaction;
import com.example.user.mapper.FundBusinessRefMapper;
import com.example.user.mapper.FundFlowMapper;
import com.example.user.mapper.FundTransactionMapper;
import com.example.user.service.FundService;
import org.apache.dubbo.config.annotation.DubboService;

import java.time.Instant;
import java.time.ZoneId;
import java.util.*;

@DubboService(timeout = 3000, retries = 0)
public class MoneyFundQueryDubboServiceImpl implements MoneyFundQueryDubboService {

    private final FundTransactionMapper transactionMapper;
    private final FundFlowMapper flowMapper;
    private final FundBusinessRefMapper businessRefMapper;
    private final FundService fundService;

    public MoneyFundQueryDubboServiceImpl(FundTransactionMapper transactionMapper,
                                          FundFlowMapper flowMapper,
                                          FundBusinessRefMapper businessRefMapper,
                                          FundService fundService) {
        this.transactionMapper = transactionMapper;
        this.flowMapper = flowMapper;
        this.businessRefMapper = businessRefMapper;
        this.fundService = fundService;
    }

    @Override
    public MoneyFundSnapshot getSnapshot(MoneyAccessContext access, MoneyFundQuery query) {
        // R01 修复：统一入口校验器，严格服务身份和委托票据检查，禁止跳过鉴权
        if (access == null) {
            throw new ForbiddenException(MoneyErrorCodes.MONEY_FORBIDDEN, "访问凭据 (access) 缺失，拒绝查询资金事实");
        }

        MoneySecuritySupport.VerifiedAccess verified;
        try {
            verified = MoneySecuritySupport.verifyEntryAccess(
                    access, "mall-user-service", "QUERY_FUND_SNAPSHOT", null);
        } catch (Exception e) {
            throw new ForbiddenException(MoneyErrorCodes.MONEY_FORBIDDEN, "访问凭据校验未通过: " + e.getMessage());
        }

        if (query == null || query.getScopeProofRef() == null || query.getScopeProofRef().isBlank()) {
            throw new ForbiddenException(MoneyErrorCodes.MONEY_SCOPE_PROOF_INVALID, "资金查询范围凭证 (scopeProofRef) 缺失");
        }

        MoneySecuritySupport.ScopeProofClaims claims = MoneySecuritySupport.verifyAndParseScopeProof(
                query.getScopeProofRef(), "mall-user-service");
        if (claims == null) {
            throw new ForbiddenException(MoneyErrorCodes.MONEY_SCOPE_PROOF_INVALID, "范围凭据无效、已过期或受众不匹配");
        }

        // R01 修复：依据验签得到的合法身份校验对象归属，买家与商家主体严格绑定
        String verifiedRole = verified.getActorRole();
        Long verifiedUserId = verified.getActorUserId();
        if ("BUYER".equalsIgnoreCase(verifiedRole)) {
            if (claims.getBuyerId() == null || !Objects.equals(claims.getBuyerId(), verifiedUserId)) {
                throw new ForbiddenException(MoneyErrorCodes.MONEY_FORBIDDEN, "无权访问此订单相关的资金记录（买家主体不匹配）");
            }
        } else if ("SELLER".equalsIgnoreCase(verifiedRole)) {
            if (claims.getSellerId() == null || !Objects.equals(claims.getSellerId(), verifiedUserId)) {
                throw new ForbiddenException(MoneyErrorCodes.MONEY_FORBIDDEN, "无权访问此订单相关的资金记录（商家主体不匹配）");
            }
        } else if (!isStaffOrFinance(verifiedRole)) {
            throw new ForbiddenException(MoneyErrorCodes.MONEY_FORBIDDEN, "未授权的访问角色: " + verifiedRole);
        }

        Set<String> targetTxNos = new LinkedHashSet<>();
        String orderNo = claims.getOrderNo();

        // 1. 根据 Order 签发的订单号直接查询
        if (orderNo != null && !orderNo.isBlank()) {
            List<FundTransaction> txsByOrder = transactionMapper.selectList(
                    new LambdaQueryWrapper<FundTransaction>().eq(FundTransaction::getOrderNo, orderNo));
            if (txsByOrder != null) {
                for (FundTransaction tx : txsByOrder) {
                    targetTxNos.add(tx.getTransactionNo());
                    // R08 修复：严格只读边界！查询过程坚决不执行持久写入回填 (backfillLegacyReferencesIfMissing)
                }
            }
        }

        // 2. R05 修复：在授权引用范围内增加精确历史幂等键查询，解决升级前/挂账流水没有 orderNo 导致的漏查
        List<String> idempotencyKeys = new ArrayList<>();
        if (orderNo != null && !orderNo.isBlank()) {
            idempotencyKeys.add("FUND_FREEZE:" + orderNo);
            idempotencyKeys.add("FUND_REFUND:" + orderNo);
            idempotencyKeys.add("FUND_SETTLE:" + orderNo);
            idempotencyKeys.add("FUND_SETTLE_AVAILABLE:" + orderNo);
        }
        if (claims.getAttempts() != null) {
            for (String att : claims.getAttempts()) {
                if (att != null && !att.isBlank()) {
                    idempotencyKeys.add("FUND_SUSPENSE_CREDIT:" + att.trim());
                }
            }
        }
        if (claims.getRefunds() != null) {
            for (String ref : claims.getRefunds()) {
                if (ref != null && !ref.isBlank()) {
                    idempotencyKeys.add("FUND_SUSPENSE_REFUND:" + ref.trim());
                    idempotencyKeys.add("FUND_REFUND_CHANNEL:" + ref.trim());
                }
            }
        }
        if (!idempotencyKeys.isEmpty()) {
            List<FundTransaction> txsByIdempotency = transactionMapper.selectList(
                    new LambdaQueryWrapper<FundTransaction>().in(FundTransaction::getIdempotencyKey, idempotencyKeys));
            if (txsByIdempotency != null) {
                for (FundTransaction tx : txsByIdempotency) {
                    targetTxNos.add(tx.getTransactionNo());
                }
            }
        }

        // 3. 根据业务引用表关联查询 (ORDER, ATTEMPT, REFUND)
        if (businessRefMapper != null) {
            if (orderNo != null && !orderNo.isBlank()) {
                List<FundBusinessRef> refs = businessRefMapper.selectByRefTypeAndNos("ORDER", List.of(orderNo));
                if (refs != null) refs.forEach(r -> targetTxNos.add(r.getTransactionNo()));
            }
            if (claims.getAttempts() != null && !claims.getAttempts().isEmpty()) {
                List<FundBusinessRef> refs = businessRefMapper.selectByRefTypeAndNos("ATTEMPT", claims.getAttempts());
                if (refs != null) refs.forEach(r -> targetTxNos.add(r.getTransactionNo()));
            }
            if (claims.getRefunds() != null && !claims.getRefunds().isEmpty()) {
                List<FundBusinessRef> refs = businessRefMapper.selectByRefTypeAndNos("REFUND", claims.getRefunds());
                if (refs != null) refs.forEach(r -> targetTxNos.add(r.getTransactionNo()));
            }
        }

        List<FundTransactionSnapshot> txSnapshots = new ArrayList<>();
        List<FundFlowSnapshot> flowSnapshots = new ArrayList<>();
        List<FundBusinessRefSnapshot> refSnapshots = new ArrayList<>();
        boolean hasUnlinkedLegacyTransactions = false;

        if (!targetTxNos.isEmpty()) {
            List<String> txList = new ArrayList<>(targetTxNos);
            List<FundTransaction> transactions = transactionMapper.selectList(
                    new LambdaQueryWrapper<FundTransaction>().in(FundTransaction::getTransactionNo, txList));
            if (transactions != null) {
                for (FundTransaction tx : transactions) {
                    txSnapshots.add(new FundTransactionSnapshot(
                            tx.getTransactionNo(),
                            tx.getBusinessType(),
                            tx.getOrderNo(),
                            tx.getWithdrawNo(),
                            tx.getMerchantId(),
                            tx.getUserId(),
                            tx.getAmount(),
                            tx.getStatus(),
                            tx.getIdempotencyKey(),
                            tx.getRemark(),
                            formatIso(tx.getCreatedTime())
                    ));
                }
            }

            List<FundFlow> flows = flowMapper.selectList(
                    new LambdaQueryWrapper<FundFlow>().in(FundFlow::getTransactionNo, txList));
            if (flows != null) {
                for (FundFlow flow : flows) {
                    flowSnapshots.add(new FundFlowSnapshot(
                            flow.getTransactionNo(),
                            flow.getOwnerType(),
                            flow.getOwnerId(),
                            flow.getAccountType(),
                            flow.getDirection(),
                            flow.getAmount(),
                            flow.getBalanceAfter(),
                            flow.getMemo(),
                            formatIso(flow.getCreatedTime())
                    ));
                }
            }

            Set<String> referencedTxNos = new HashSet<>();
            if (businessRefMapper != null) {
                List<FundBusinessRef> refs = businessRefMapper.selectByTransactionNos(txList);
                if (refs != null) {
                    for (FundBusinessRef ref : refs) {
                        referencedTxNos.add(ref.getTransactionNo());
                        refSnapshots.add(new FundBusinessRefSnapshot(
                                ref.getTransactionNo(),
                                ref.getRefType(),
                                ref.getRefNo(),
                                ref.getLinkOrigin(),
                                formatIso(ref.getCreatedAt())
                        ));
                    }
                }
            }

            // R08 & R05 修复：对数据库无物理引用的历史事务，仅在内存中派生引用说明，不触发任何数据库写操作
            if (transactions != null) {
                for (FundTransaction tx : transactions) {
                    if (!referencedTxNos.contains(tx.getTransactionNo())) {
                        hasUnlinkedLegacyTransactions = true;
                        deriveLegacyRefInMemory(tx, refSnapshots);
                    }
                }
            }
        }

        Map<String, String> sources = new LinkedHashMap<>();
        boolean complete;
        if (txSnapshots.isEmpty()) {
            // R05 修复：找不到任何事务时，明确返回 NOT_FOUND，不伪报 OK 或 complete=true
            sources.put("USER_LEDGER", MoneyCoverageStatus.NOT_FOUND.name());
            sources.put("BUSINESS_REF", MoneyCoverageStatus.NOT_FOUND.name());
            complete = false;
        } else {
            sources.put("USER_LEDGER", MoneyCoverageStatus.OK.name());
            sources.put("BUSINESS_REF", hasUnlinkedLegacyTransactions
                    ? MoneyCoverageStatus.LEGACY_UNLINKED.name()
                    : MoneyCoverageStatus.OK.name());
            complete = true;
        }

        CoverageInfo coverage = new CoverageInfo(sources, complete, false, null, complete ? "COMPLETE" : "EMPTY");

        return new MoneyFundSnapshot(
                txSnapshots,
                flowSnapshots,
                refSnapshots,
                coverage,
                Instant.now().toString()
        );
    }

    private void deriveLegacyRefInMemory(FundTransaction tx, List<FundBusinessRefSnapshot> refSnapshots) {
        String key = tx.getIdempotencyKey();
        if (key == null) return;
        if (key.startsWith("FUND_SUSPENSE_CREDIT:")) {
            String attemptNo = key.substring("FUND_SUSPENSE_CREDIT:".length());
            refSnapshots.add(new FundBusinessRefSnapshot(tx.getTransactionNo(), "ATTEMPT", attemptNo, "LEGACY_DERIVED", formatIso(tx.getCreatedTime())));
        } else if (key.startsWith("FUND_SUSPENSE_REFUND:")) {
            String refundNo = key.substring("FUND_SUSPENSE_REFUND:".length());
            refSnapshots.add(new FundBusinessRefSnapshot(tx.getTransactionNo(), "REFUND", refundNo, "LEGACY_DERIVED", formatIso(tx.getCreatedTime())));
        } else if (key.startsWith("FUND_REFUND_CHANNEL:")) {
            String refundNo = key.substring("FUND_REFUND_CHANNEL:".length());
            refSnapshots.add(new FundBusinessRefSnapshot(tx.getTransactionNo(), "REFUND", refundNo, "LEGACY_DERIVED", formatIso(tx.getCreatedTime())));
        } else if (key.startsWith("FUND_FREEZE:") || key.startsWith("FUND_REFUND:") || key.startsWith("FUND_SETTLE:")) {
            String orderNo = tx.getOrderNo();
            if (orderNo != null && !orderNo.isBlank()) {
                refSnapshots.add(new FundBusinessRefSnapshot(tx.getTransactionNo(), "ORDER", orderNo, "LEGACY_DERIVED", formatIso(tx.getCreatedTime())));
            }
        }
    }

    private static String formatIso(java.time.LocalDateTime ldt) {
        if (ldt == null) return null;
        // R11 修复：本地时间 Asia/Shanghai 转真实 UTC Instant ISO 格式
        return ldt.atZone(ZoneId.of("Asia/Shanghai")).toInstant().toString();
    }

    private boolean isStaffOrFinance(String role) {
        if (role == null) return false;
        String r = role.toUpperCase();
        return "ADMIN".equals(r) || "FINANCE".equals(r) || "CUSTOMER_STAFF".equals(r) || "SYSTEM".equals(r);
    }
}
