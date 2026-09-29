package com.example.user.service;

import api.trade.FundOperationResult;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.user.entity.FundFlow;
import com.example.user.entity.FundTransaction;
import com.example.user.entity.PlatformAccount;
import com.example.user.entity.UserAccount;
import com.example.user.mapper.FundFlowMapper;
import com.example.user.mapper.FundTransactionMapper;
import com.example.user.mapper.PlatformAccountMapper;
import com.example.user.mapper.UserAccountMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class FundService {
    private final FundTransactionMapper transactionMapper;
    private final FundFlowMapper flowMapper;
    private final UserAccountMapper accountMapper;
    private final PlatformAccountMapper platformMapper;

    public FundService(FundTransactionMapper transactionMapper, FundFlowMapper flowMapper,
                       UserAccountMapper accountMapper, PlatformAccountMapper platformMapper) {
        this.transactionMapper = transactionMapper;
        this.flowMapper = flowMapper;
        this.accountMapper = accountMapper;
        this.platformMapper = platformMapper;
    }

    @Transactional(rollbackFor = Exception.class)
    public FundOperationResult freezeEscrow(String orderNo, Long sellerId, Long merchantId, BigDecimal amount) {
        return execute("PAY_FREEZE", orderNo, null, sellerId, merchantId, amount, tx -> {
            ensureUserAccount(sellerId);
            requireSuccess(platformMapper.creditEscrow(amount), "平台托管账户记账失败");
            flow(tx, "PLATFORM", null, "ESCROW", "CREDIT", amount, null, "支付成功，进入托管");
            flow(tx, "EXTERNAL", null, "EXTERNAL_CHANNEL", "DEBIT", amount, null, "买家 Mock 支付");
        });
    }

    @Transactional(rollbackFor = Exception.class)
    public FundOperationResult refundEscrow(String orderNo, Long buyerId, BigDecimal amount) {
        return execute("REFUND", orderNo, null, buyerId, null, amount, tx -> {
            requireSuccess(platformMapper.debitEscrow(amount), "托管资金不足");
            ensureUserAccount(buyerId);
            requireSuccess(accountMapper.creditAvailable(buyerId, amount), "买家账户记账失败");
            flow(tx, "PLATFORM", null, "ESCROW", "DEBIT", amount, null, "托管退款");
            flow(tx, "USER", buyerId, "AVAILABLE", "CREDIT", amount, amount, "仲裁退款");
        });
    }

    @Transactional(rollbackFor = Exception.class)
    public FundOperationResult settle(String orderNo, Long sellerId, Long merchantId,
                                      BigDecimal orderAmount, BigDecimal feeAmount, BigDecimal sellerIncome) {
        validateSettleAmount(orderAmount, feeAmount, sellerIncome);
        return execute("SETTLE", orderNo, null, sellerId, merchantId, orderAmount, tx -> {
            ensureUserAccount(sellerId);
            requireSuccess(platformMapper.debitEscrow(orderAmount), "托管资金不足");
            requireSuccess(platformMapper.creditRevenue(feeAmount), "平台收入记账失败");
            requireSuccess(accountMapper.creditPending(sellerId, sellerIncome), "卖家待结算记账失败");
            flow(tx, "PLATFORM", null, "ESCROW", "DEBIT", orderAmount, null, "托管释放");
            flow(tx, "PLATFORM", null, "REVENUE", "CREDIT", feeAmount, null, "平台手续费");
            flow(tx, "USER", sellerId, "PENDING_SETTLE", "CREDIT", sellerIncome, sellerIncome, "订单结算");
        });
    }

    @Transactional(rollbackFor = Exception.class)
    public FundOperationResult withdrawFreeze(String withdrawNo, Long userId, Long merchantId, BigDecimal amount) {
        return execute("WITHDRAW_FREEZE", null, withdrawNo, userId, merchantId, amount, tx -> {
            ensureUserAccount(userId);
            requireSuccess(accountMapper.freezeForWithdraw(userId, amount), "可提现余额不足");
            flow(tx, "USER", userId, "AVAILABLE", "DEBIT", amount, amount, "申请提现");
            flow(tx, "USER", userId, "FROZEN", "CREDIT", amount, amount, "提现冻结");
        });
    }

    @Transactional(rollbackFor = Exception.class)
    public FundOperationResult withdrawPayout(String withdrawNo, Long userId, Long merchantId, BigDecimal amount) {
        return execute("WITHDRAW_PAYOUT", null, withdrawNo, userId, merchantId, amount, tx -> {
            requireSuccess(accountMapper.payoutFrozen(userId, amount), "提现冻结资金不足");
            requireSuccess(platformMapper.creditWithdrawPending(amount), "平台提现中账户记账失败");
            requireSuccess(platformMapper.debitWithdrawPending(amount), "平台提现中账户扣减失败");
            flow(tx, "USER", userId, "FROZEN", "DEBIT", amount, amount, "Mock 打款");
            flow(tx, "PLATFORM", null, "WITHDRAW_PENDING", "CREDIT", amount, null, "Mock 打款");
            flow(tx, "PLATFORM", null, "WITHDRAW_PENDING", "DEBIT", amount, null, "Mock 打款完成");
            flow(tx, "EXTERNAL", null, "EXTERNAL_CHANNEL", "DEBIT", amount, null, "资金流出平台");
        });
    }

    @Transactional(rollbackFor = Exception.class)
    public FundOperationResult withdrawReject(String withdrawNo, Long userId, Long merchantId, BigDecimal amount) {
        return execute("WITHDRAW_REJECT", null, withdrawNo, userId, merchantId, amount, tx -> {
            requireSuccess(accountMapper.unfreezeForReject(userId, amount), "提现冻结资金不足");
            flow(tx, "USER", userId, "FROZEN", "DEBIT", amount, amount, "提现驳回");
            flow(tx, "USER", userId, "AVAILABLE", "CREDIT", amount, amount, "提现驳回返还");
        });
    }

    public UserAccount getAccount(Long userId) {
        UserAccount account = accountMapper.selectById(userId);
        if (account == null) {
            account = new UserAccount(userId, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0,
                    LocalDateTime.now(), LocalDateTime.now());
            accountMapper.insert(account);
        }
        return account;
    }

    public BigDecimal getPlatformEscrowAmount() {
        ensurePlatformAccount();
        PlatformAccount account = platformMapper.selectOne(new LambdaQueryWrapper<PlatformAccount>()
                .eq(PlatformAccount::getAccountCode, "PLATFORM_MAIN"));
        return account == null ? BigDecimal.ZERO : account.getEscrowAmount();
    }

    public List<FundFlow> getFlows(Long userId) {
        return flowMapper.selectList(new LambdaQueryWrapper<FundFlow>()
                .eq(FundFlow::getOwnerType, "USER")
                .eq(FundFlow::getOwnerId, userId)
                .orderByDesc(FundFlow::getId)
                .last("LIMIT 100"));
    }

    @Transactional(rollbackFor = Exception.class)
    public FundOperationResult settlePendingToAvailable(String orderNo, Long userId, BigDecimal amount) {
        return execute("SETTLE_AVAILABLE", orderNo, null, userId, null, amount, tx -> {
            ensureUserAccount(userId);
            requireSuccess(accountMapper.settleToAvailable(userId, amount), "待结算余额不足");
            flow(tx, "USER", userId, "PENDING_SETTLE", "DEBIT", amount, amount, "结算转可用余额");
            flow(tx, "USER", userId, "AVAILABLE", "CREDIT", amount, amount, "结算转可用余额");
        });
    }

    @Transactional(rollbackFor = Exception.class)
    public FundOperationResult payDeposit(String depositNo, Long merchantId, Long userId, BigDecimal amount) {
        if (depositNo == null || depositNo.isBlank()) {
            return FundOperationResult.fail("保证金缴纳单号不能为空");
        }
        return execute("DEPOSIT_PAY", depositNo, null, userId, merchantId, amount, tx -> {
            ensureUserAccount(userId);
            requireSuccess(platformMapper.creditDeposit(amount), "平台保证金账户记账失败");
            flow(tx, "PLATFORM", null, "DEPOSIT", "CREDIT", amount, null, "商家缴纳 Mock 保证金");
            flow(tx, "EXTERNAL", null, "EXTERNAL_CHANNEL", "DEBIT", amount, null, "Mock 保证金支付");
        });
    }

    public void ensurePlatformAccount() {
        PlatformAccount account = platformMapper.selectOne(new LambdaQueryWrapper<PlatformAccount>()
                .eq(PlatformAccount::getAccountCode, "PLATFORM_MAIN"));
        if (account == null) {
            account = new PlatformAccount();
            account.setAccountCode("PLATFORM_MAIN");
            account.setEscrowAmount(BigDecimal.ZERO);
            account.setRevenueAmount(BigDecimal.ZERO);
            account.setWithdrawPendingAmount(BigDecimal.ZERO);
            account.setDepositAmount(BigDecimal.ZERO);
            account.setVersion(0);
            account.setCreatedTime(LocalDateTime.now());
            account.setUpdatedTime(LocalDateTime.now());
            try {
                platformMapper.insert(account);
            } catch (org.springframework.dao.DuplicateKeyException ignored) {
                // 并发初始化时以数据库唯一键为准。
            }
        }
    }

    private FundOperationResult execute(String businessType, String orderNo, String withdrawNo, Long userId,
                                        Long merchantId, BigDecimal amount, FundAction action) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            return FundOperationResult.fail("金额必须大于 0");
        }
        ensurePlatformAccount();
        String idempotencyKey = businessType + ":" + (orderNo != null ? orderNo : withdrawNo);
        FundTransaction existing = transactionMapper.selectOne(new LambdaQueryWrapper<FundTransaction>()
                .eq(FundTransaction::getIdempotencyKey, idempotencyKey));
        if (existing != null) {
            if (!isSameIdempotentRequest(existing, businessType, orderNo, withdrawNo, userId, merchantId, amount)) {
                return FundOperationResult.fail("幂等请求参数不一致");
            }
            return FundOperationResult.success(existing.getTransactionNo());
        }

        FundTransaction transaction = new FundTransaction();
        transaction.setTransactionNo("FUND" + UUID.randomUUID().toString().replace("-", ""));
        transaction.setBusinessType(businessType);
        transaction.setOrderNo(orderNo);
        transaction.setWithdrawNo(withdrawNo);
        transaction.setMerchantId(merchantId);
        transaction.setUserId(userId);
        transaction.setAmount(amount);
        transaction.setStatus("SUCCESS");
        transaction.setIdempotencyKey(idempotencyKey);
        transaction.setRemark(businessType);
        transaction.setCreatedTime(LocalDateTime.now());
        try {
            transactionMapper.insert(transaction);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            FundTransaction winner = transactionMapper.selectOne(new LambdaQueryWrapper<FundTransaction>()
                    .eq(FundTransaction::getIdempotencyKey, idempotencyKey));
            if (winner == null || !isSameIdempotentRequest(winner, businessType, orderNo, withdrawNo,
                    userId, merchantId, amount)) {
                return FundOperationResult.fail("幂等请求参数不一致");
            }
            return FundOperationResult.success(winner.getTransactionNo());
        }
        action.apply(transaction);
        return FundOperationResult.success(transaction.getTransactionNo());
    }

    private boolean isSameIdempotentRequest(FundTransaction existing, String businessType, String orderNo,
                                            String withdrawNo, Long userId, Long merchantId, BigDecimal amount) {
        return businessType.equals(existing.getBusinessType())
                && Objects.equals(orderNo, existing.getOrderNo())
                && Objects.equals(withdrawNo, existing.getWithdrawNo())
                && Objects.equals(userId, existing.getUserId())
                && Objects.equals(merchantId, existing.getMerchantId())
                && amount.compareTo(existing.getAmount()) == 0;
    }

    private void ensureUserAccount(Long userId) {
        if (accountMapper.selectById(userId) == null) {
            UserAccount account = new UserAccount(userId, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0,
                    LocalDateTime.now(), LocalDateTime.now());
            try {
                accountMapper.insert(account);
            } catch (org.springframework.dao.DuplicateKeyException ignored) {
                // 并发创建时直接使用既有账户。
            }
        }
    }

    private void flow(FundTransaction transaction, String ownerType, Long ownerId, String accountType,
                      String direction, BigDecimal amount, BigDecimal balanceAfter, String memo) {
        FundFlow fundFlow = new FundFlow();
        fundFlow.setTransactionNo(transaction.getTransactionNo());
        fundFlow.setOwnerType(ownerType);
        fundFlow.setOwnerId(ownerId);
        fundFlow.setAccountType(accountType);
        fundFlow.setDirection(direction);
        fundFlow.setAmount(amount);
        fundFlow.setBalanceAfter(balanceAfter);
        fundFlow.setMemo(memo);
        fundFlow.setCreatedTime(LocalDateTime.now());
        flowMapper.insert(fundFlow);
    }

    private void validateSettleAmount(BigDecimal orderAmount, BigDecimal feeAmount, BigDecimal sellerIncome) {
        if (orderAmount == null || feeAmount == null || sellerIncome == null
                || orderAmount.compareTo(BigDecimal.ZERO) <= 0 || feeAmount.compareTo(BigDecimal.ZERO) < 0
                || sellerIncome.compareTo(BigDecimal.ZERO) <= 0
                || feeAmount.add(sellerIncome).compareTo(orderAmount) != 0) {
            throw new IllegalArgumentException("结算金额不合法");
        }
    }

    private void requireSuccess(int rows, String message) {
        if (rows <= 0) {
            throw new IllegalStateException(message);
        }
    }

    @FunctionalInterface
    private interface FundAction {
        void apply(FundTransaction transaction);
    }
}
