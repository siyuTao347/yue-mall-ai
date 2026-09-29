package com.example.user.service;

import api.trade.FundOperationResult;
import api.risk.RiskCommandDTO;
import api.risk.RiskDecisionResult;
import api.risk.RiskEvaluateRequest;
import api.risk.RiskSupport;
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
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class WithdrawService {
    private static final Pattern CLIENT_TOKEN_PATTERN = Pattern.compile("[A-Za-z0-9_-]{8,32}");
    private static final List<String> RISK_BLOCKED_STATUSES =
            List.of("MANUAL_REVIEW", "FROZEN", "REJECTED");

    private final WithdrawRequestMapper withdrawMapper;
    private final MerchantService merchantService;
    private final FundService fundService;
    private final RiskClient riskClient;

    public WithdrawService(WithdrawRequestMapper withdrawMapper, MerchantService merchantService,
                           FundService fundService, RiskClient riskClient) {
        this.withdrawMapper = withdrawMapper;
        this.merchantService = merchantService;
        this.fundService = fundService;
        this.riskClient = riskClient;
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
        Merchant merchant = merchantService.getApprovedMerchant(merchantId, userId);
        fundService.getAccount(userId);
        WithdrawRequest request = new WithdrawRequest();
        request.setWithdrawNo(withdrawNo);
        request.setMerchantId(merchantId);
        request.setUserId(userId);
        request.setAmount(amount);
        request.setMockAccount(mockAccount);
        request.setStatus("SUBMITTED");
        request.setRiskStatus("NORMAL");
        request.setRiskLevel("LOW");
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

        String eventNo = RiskSupport.nextEventNo("WITHDRAW");
        RiskDecisionResult decision = riskClient.evaluate(RiskEvaluateRequest.builder()
                .eventNo(eventNo)
                .scene("WITHDRAW")
                .eventType("APPLY")
                .bizType("WITHDRAW")
                .bizNo(withdrawNo)
                .userId(userId)
                .merchantId(merchantId)
                .withdrawNo(withdrawNo)
                .amount(amount)
                .ipHash(riskClient.ipHash())
                .deviceHash(riskClient.deviceHash())
                .payload(withdrawPayload(request, merchant, mockAccount))
                .build());
        String riskStatus = RiskSupport.toRiskStatus(decision.getAction());
        if (!"NORMAL".equals(riskStatus)) {
            withdrawMapper.updateRiskStatus(withdrawNo, riskStatus, decision.getRiskLevel(),
                    decision.getDecisionNo(), decision.getMessage(), LocalDateTime.now());
            request.setRiskStatus(riskStatus);
            request.setRiskLevel(decision.getRiskLevel());
            request.setRiskDecisionNo(decision.getDecisionNo());
            request.setRiskReason(decision.getMessage());
        }
        if (RiskDecisionResult.ACTION_REJECT.equals(decision.getAction())) {
            withdrawMapper.updateById(rejectedRequest(request, decision));
            riskClient.confirmAfterCommit(eventNo);
            return request;
        }

        FundOperationResult freezeResult = fundService.withdrawFreeze(
                withdrawNo, userId, merchantId, amount);
        if (!freezeResult.isSuccess()) {
            throw new IllegalStateException(freezeResult.getMessage());
        }
        riskClient.confirmAfterCommit(eventNo);
        return request;
    }

    @Transactional(rollbackFor = Exception.class)
    public WithdrawRequest audit(String withdrawNo, Long adminId, boolean approved, String reason) {
        WithdrawRequest request = findByNo(withdrawNo);
        if (request == null || !"SUBMITTED".equals(request.getStatus())) {
            throw new IllegalArgumentException("提现单不存在或已处理");
        }
        if (RISK_BLOCKED_STATUSES.contains(nullToNormal(request.getRiskStatus()))) {
            throw new IllegalStateException("提现安全审核中或已冻结，禁止放款");
        }
        if (request.getPayoutDelayUntil() != null && request.getPayoutDelayUntil().isAfter(LocalDateTime.now())) {
            throw new IllegalStateException("提现仍在风控延迟观察期，禁止放款");
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

    @Transactional(rollbackFor = Exception.class)
    public boolean rejectByRisk(RiskCommandDTO command) {
        WithdrawRequest request = findByNo(command.getWithdrawNo());
        if (request == null || !"SUBMITTED".equals(request.getStatus())
                || "REJECTED".equals(nullToNormal(request.getRiskStatus()))) {
            return false;
        }
        FundOperationResult result = fundService.withdrawReject(command.getWithdrawNo(),
                request.getUserId(), request.getMerchantId(), request.getAmount());
        if (!result.isSuccess()) {
            throw new IllegalStateException(result.getMessage());
        }
        return withdrawMapper.rejectByRisk(command.getWithdrawNo(), riskLevel(command),
                command.getDecisionNo(), reason(command), command.getOperatorId(),
                LocalDateTime.now()) > 0;
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

    private Map<String, Object> withdrawPayload(WithdrawRequest request, Merchant merchant, String mockAccount) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("withdrawId", request.getId());
        payload.put("merchantAgeHours", RiskSupport.hoursBetween(merchant.getCreatedTime(), null));
        payload.put("withdrawAccountHash", riskClient.identityHash(mockAccount));
        return payload;
    }

    private WithdrawRequest rejectedRequest(WithdrawRequest request, RiskDecisionResult decision) {
        request.setStatus("REJECTED");
        request.setAuditReason(decision.getMessage());
        request.setRiskStatus("REJECTED");
        request.setRiskLevel(decision.getRiskLevel());
        request.setRiskDecisionNo(decision.getDecisionNo());
        request.setRiskReason(decision.getMessage());
        request.setUpdatedTime(LocalDateTime.now());
        return request;
    }

    private String nullToNormal(String value) {
        return value == null || value.isBlank() ? "NORMAL" : value;
    }

    private String reason(RiskCommandDTO command) {
        return command.getReason() == null || command.getReason().isBlank()
                ? "风控案件人工拒绝提现" : command.getReason();
    }

    private String riskLevel(RiskCommandDTO command) {
        Map<String, Object> params = command.getActionParams();
        Object value = params == null ? null : params.get("riskLevel");
        return value == null || String.valueOf(value).isBlank() ? "HIGH" : String.valueOf(value);
    }
}
