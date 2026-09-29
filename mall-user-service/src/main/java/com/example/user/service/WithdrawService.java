package com.example.user.service;

import api.trade.FundOperationResult;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.user.entity.Merchant;
import com.example.user.entity.WithdrawRequest;
import com.example.user.mapper.WithdrawRequestMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.regex.Pattern;

@Service
public class WithdrawService {
    private static final Pattern CLIENT_TOKEN_PATTERN = Pattern.compile("[A-Za-z0-9_-]{8,32}");

    private final WithdrawRequestMapper withdrawMapper;
    private final MerchantService merchantService;
    private final FundService fundService;

    public WithdrawService(WithdrawRequestMapper withdrawMapper, MerchantService merchantService, FundService fundService) {
        this.withdrawMapper = withdrawMapper;
        this.merchantService = merchantService;
        this.fundService = fundService;
    }

    @Transactional(rollbackFor = Exception.class)
    public WithdrawRequest apply(Long merchantId, Long userId, BigDecimal amount,
                                 String mockAccount, String clientToken) {
        if (amount == null || amount.compareTo(new BigDecimal("10.00")) < 0) {
            throw new IllegalArgumentException("最低提现金额为 10 元");
        }
        if (mockAccount == null || mockAccount.isBlank()) {
            throw new IllegalArgumentException("模拟收款账户不能为空");
        }
        if (clientToken == null || !CLIENT_TOKEN_PATTERN.matcher(clientToken).matches()) {
            throw new IllegalArgumentException("提现请求标识不合法");
        }
        String withdrawNo = "W" + userId + "-" + clientToken;
        WithdrawRequest existing = findByNo(withdrawNo);
        if (existing != null) {
            ensureSameWithdrawRequest(existing, merchantId, userId, amount, mockAccount);
            return existing;
        }
        merchantService.getApprovedMerchant(merchantId, userId);
        fundService.getAccount(userId);

        FundOperationResult freezeResult = fundService.withdrawFreeze(
                withdrawNo, userId, merchantId, amount);
        if (!freezeResult.isSuccess()) {
            throw new IllegalStateException(freezeResult.getMessage());
        }

        WithdrawRequest request = new WithdrawRequest();
        request.setWithdrawNo(withdrawNo);
        request.setMerchantId(merchantId);
        request.setUserId(userId);
        request.setAmount(amount);
        request.setMockAccount(mockAccount);
        request.setStatus("SUBMITTED");
        request.setCreatedTime(LocalDateTime.now());
        request.setUpdatedTime(LocalDateTime.now());
        try {
            withdrawMapper.insert(request);
        } catch (DuplicateKeyException e) {
            WithdrawRequest winner = findByNo(withdrawNo);
            if (winner == null) {
                throw new IllegalStateException("提现单创建失败，请稍后重试");
            }
            ensureSameWithdrawRequest(winner, merchantId, userId, amount, mockAccount);
            return winner;
        }
        return request;
    }

    @Transactional(rollbackFor = Exception.class)
    public WithdrawRequest audit(String withdrawNo, Long adminId, boolean approved, String reason) {
        WithdrawRequest request = findByNo(withdrawNo);
        if (request == null || !"SUBMITTED".equals(request.getStatus())) {
            throw new IllegalArgumentException("提现单不存在或已处理");
        }
        Merchant merchant = merchantService.getApprovedMerchantById(request.getMerchantId());
        FundOperationResult result = approved
                ? fundService.withdrawPayout(withdrawNo, request.getUserId(), request.getMerchantId(), request.getAmount())
                : fundService.withdrawReject(withdrawNo, request.getUserId(), request.getMerchantId(), request.getAmount());
        if (!result.isSuccess()) {
            throw new IllegalStateException(result.getMessage());
        }
        request.setStatus(approved ? "PAYOUT_SUCCESS" : "REJECTED");
        request.setAuditAdminId(adminId);
        request.setAuditReason(reason);
        request.setMockPayoutNo(approved ? result.getTransactionNo() : null);
        request.setUpdatedTime(LocalDateTime.now());
        withdrawMapper.updateById(request);
        return request;
    }

    public List<WithdrawRequest> listByUser(Long userId) {
        return withdrawMapper.selectList(new LambdaQueryWrapper<WithdrawRequest>()
                .eq(WithdrawRequest::getUserId, userId)
                .orderByDesc(WithdrawRequest::getId));
    }

    public List<WithdrawRequest> listPending() {
        return withdrawMapper.selectList(new LambdaQueryWrapper<WithdrawRequest>()
                .eq(WithdrawRequest::getStatus, "SUBMITTED")
                .orderByAsc(WithdrawRequest::getId));
    }

    private WithdrawRequest findByNo(String withdrawNo) {
        return withdrawMapper.selectOne(new LambdaQueryWrapper<WithdrawRequest>()
                .eq(WithdrawRequest::getWithdrawNo, withdrawNo));
    }

    private void ensureSameWithdrawRequest(WithdrawRequest request, Long merchantId, Long userId,
                                           BigDecimal amount, String mockAccount) {
        if (!request.getMerchantId().equals(merchantId) || !request.getUserId().equals(userId)
                || request.getAmount().compareTo(amount) != 0
                || !request.getMockAccount().equals(mockAccount)) {
            throw new IllegalArgumentException("提现请求标识已被使用");
        }
    }
}
