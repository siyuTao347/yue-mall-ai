package com.example.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import api.trade.FundOperationResult;
import api.risk.RiskDecisionResult;
import api.risk.RiskEvaluateRequest;
import api.risk.RiskSupport;
import api.risk.SensitiveWordHitDTO;
import api.risk.SensitiveWordScanner;
import com.example.user.entity.Merchant;
import com.example.user.entity.MerchantAudit;
import com.example.user.entity.MerchantCredit;
import com.example.user.entity.MerchantDeposit;
import com.example.user.entity.User;
import com.example.user.mapper.MerchantAuditMapper;
import com.example.user.mapper.MerchantCreditMapper;
import com.example.user.mapper.MerchantDepositMapper;
import com.example.user.mapper.MerchantMapper;
import com.example.user.mapper.UserMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
public class MerchantService {
    public static final String STATUS_SUBMITTED = "SUBMITTED";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_FROZEN = "FROZEN";
    private static final List<String> RISK_BLOCKED_STATUSES =
            List.of("MANUAL_REVIEW", "FROZEN", "REJECTED");

    private final MerchantMapper merchantMapper;
    private final MerchantAuditMapper auditMapper;
    private final MerchantDepositMapper depositMapper;
    private final MerchantCreditMapper creditMapper;
    private final UserMapper userMapper;
    private final FundService fundService;
    private final RiskClient riskClient;

    public MerchantService(MerchantMapper merchantMapper, MerchantAuditMapper auditMapper,
                           MerchantDepositMapper depositMapper, MerchantCreditMapper creditMapper,
                           UserMapper userMapper, FundService fundService, RiskClient riskClient) {
        this.merchantMapper = merchantMapper;
        this.auditMapper = auditMapper;
        this.depositMapper = depositMapper;
        this.creditMapper = creditMapper;
        this.userMapper = userMapper;
        this.fundService = fundService;
        this.riskClient = riskClient;
    }

    @Transactional(rollbackFor = Exception.class)
    public Merchant apply(Long userId, String merchantName, String contactEmail, String introduction) {
        if (userId == null || merchantName == null || merchantName.isBlank() || contactEmail == null || contactEmail.isBlank()) {
            throw new IllegalArgumentException("用户、商家名称和联系邮箱不能为空");
        }
        Merchant merchant = new Merchant();
        merchant.setUserId(userId);
        merchant.setMerchantName(merchantName.trim());
        merchant.setContactEmail(contactEmail.trim());
        merchant.setIntroduction(introduction);
        merchant.setStatus(STATUS_SUBMITTED);
        merchant.setLevel(1);
        merchant.setCreatedTime(LocalDateTime.now());
        merchant.setUpdatedTime(LocalDateTime.now());
        merchant.setRiskStatus("NORMAL");
        merchant.setRiskLevel("LOW");
        String content = merchantContent(merchantName, contactEmail, introduction);
        List<SensitiveWordHitDTO> sensitiveHits =
                SensitiveWordScanner.scan(content, riskClient.sensitiveWords());
        requireLocalSafeMerchant(sensitiveHits);
        merchantMapper.insert(merchant);

        String eventNo = RiskSupport.nextEventNo("MERCHANT");
        RiskDecisionResult decision = riskClient.evaluate(RiskEvaluateRequest.builder()
                .eventNo(eventNo)
                .scene("MERCHANT")
                .eventType("CREATE")
                .bizType("MERCHANT")
                .bizNo(String.valueOf(merchant.getId()))
                .userId(userId)
                .merchantId(merchant.getId())
                .ipHash(riskClient.ipHash())
                .deviceHash(riskClient.deviceHash())
                .payload(merchantPayload(content, sensitiveHits))
                .sensitiveHits(sensitiveHits)
                .build());

        String riskStatus = RiskSupport.toRiskStatus(decision.getAction());
        if (!"NORMAL".equals(riskStatus)) {
            merchantMapper.updateRiskStatus(merchant.getId(), riskStatus, decision.getRiskLevel(),
                    decision.getDecisionNo(), decision.getMessage(), LocalDateTime.now());
            merchant.setRiskStatus(riskStatus);
            merchant.setRiskLevel(decision.getRiskLevel());
            merchant.setRiskDecisionNo(decision.getDecisionNo());
            merchant.setRiskReason(decision.getMessage());
        }
        if (RiskDecisionResult.ACTION_REJECT.equals(decision.getAction())) {
            String reason = decision.getMessage();
            merchantMapper.updateStatus(merchant.getId(), STATUS_SUBMITTED, STATUS_REJECTED,
                    reason, LocalDateTime.now());
            merchant.setStatus(STATUS_REJECTED);
            merchant.setRejectReason(reason);
            insertAudit(merchant.getId(), "RISK_REJECT", null, reason);
            riskClient.confirmAfterCommit(eventNo);
            return merchant;
        }
        insertAudit(merchant.getId(), "SUBMIT", null, "商家提交入驻申请");
        if (RiskDecisionResult.ACTION_FREEZE.equals(decision.getAction())
                || RiskDecisionResult.ACTION_MANUAL_REVIEW.equals(decision.getAction())) {
            insertAudit(merchant.getId(), "RISK_REVIEW", null, decision.getMessage());
            riskClient.confirmAfterCommit(eventNo);
            return merchant;
        }
        riskClient.confirmAfterCommit(eventNo);
        return merchant;
    }

    @Transactional(rollbackFor = Exception.class)
    public Merchant audit(Long merchantId, Long auditorId, String action, String reason) {
        Merchant merchant = merchantMapper.selectById(merchantId);
        if (merchant == null) {
            throw new IllegalArgumentException("商家不存在");
        }
        String currentStatus = merchant.getStatus();
        boolean valid = switch (action) {
            case "APPROVE" -> STATUS_SUBMITTED.equals(currentStatus) || STATUS_FROZEN.equals(currentStatus);
            case "REJECT" -> STATUS_SUBMITTED.equals(currentStatus);
            case "FREEZE" -> STATUS_APPROVED.equals(currentStatus);
            case "UNFREEZE" -> STATUS_FROZEN.equals(currentStatus);
            default -> throw new IllegalArgumentException("不支持的操作");
        };
        if (!valid) {
            throw new IllegalArgumentException("商家当前状态不允许该操作");
        }
        if ("APPROVE".equals(action) && RISK_BLOCKED_STATUSES.contains(nullToNormal(merchant.getRiskStatus()))) {
            throw new IllegalStateException("商家安全审核中或已冻结，不能通过入驻审核");
        }
        String targetStatus = switch (action) {
            case "APPROVE" -> STATUS_APPROVED;
            case "REJECT" -> STATUS_REJECTED;
            case "FREEZE" -> STATUS_FROZEN;
            case "UNFREEZE" -> STATUS_APPROVED;
            default -> throw new IllegalArgumentException("不支持的操作");
        };
        String rejectReason = "REJECT".equals(action) ? reason : null;
        LocalDateTime now = LocalDateTime.now();
        if (merchantMapper.updateStatus(merchantId, currentStatus, targetStatus, rejectReason, now) <= 0) {
            throw new IllegalStateException("商家审核状态更新失败，请刷新后重试");
        }
        merchant.setStatus(targetStatus);
        merchant.setRejectReason(rejectReason);
        merchant.setUpdatedTime(now);
        insertAudit(merchantId, action, auditorId, reason);
        return merchant;
    }

    @Transactional(rollbackFor = Exception.class)
    public MerchantDeposit payDeposit(String depositNo, Long merchantId, Long userId, BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("保证金金额必须大于 0");
        }
        if (depositNo == null || depositNo.isBlank()) {
            throw new IllegalArgumentException("保证金缴纳单号不能为空");
        }
        Merchant merchant = getApprovedMerchant(merchantId, userId);
        MerchantDeposit deposit = depositMapper.selectOne(new LambdaQueryWrapper<MerchantDeposit>()
                .eq(MerchantDeposit::getMerchantId, merchantId));
        if (deposit == null) {
            deposit = new MerchantDeposit();
            deposit.setMerchantId(merchantId);
            deposit.setTotalAmount(BigDecimal.ZERO);
            deposit.setFrozenAmount(BigDecimal.ZERO);
            deposit.setDeductedAmount(BigDecimal.ZERO);
            deposit.setCreatedTime(LocalDateTime.now());
            deposit.setUpdatedTime(LocalDateTime.now());
            depositMapper.insert(deposit);
        }
        FundOperationResult result = fundService.payDeposit(depositNo, merchantId, userId, amount);
        if (!result.isSuccess()) {
            throw new IllegalStateException(result.getMessage());
        }
        if (depositMapper.increaseTotal(merchantId, deposit.getTotalAmount(), amount) <= 0) {
            throw new IllegalStateException("保证金账户已变化，请刷新后重试");
        }
        deposit.setTotalAmount(deposit.getTotalAmount().add(amount));
        deposit.setUpdatedTime(LocalDateTime.now());
        return deposit;
    }

    public MerchantDeposit getDeposit(Long merchantId, Long userId) {
        getApprovedMerchant(merchantId, userId);
        return findDeposit(merchantId);
    }

    public Merchant getByUserId(Long userId) {
        return merchantMapper.selectOne(new LambdaQueryWrapper<Merchant>().eq(Merchant::getUserId, userId));
    }

    public Merchant getApprovedMerchant(Long merchantId, Long userId) {
        Merchant merchant = merchantMapper.selectById(merchantId);
        if (merchant == null || !merchant.getUserId().equals(userId) || !STATUS_APPROVED.equals(merchant.getStatus())
                || RISK_BLOCKED_STATUSES.contains(nullToNormal(merchant.getRiskStatus()))) {
            throw new IllegalArgumentException("商家不存在、未通过审核或不属于当前用户");
        }
        return merchant;
    }

    public List<Merchant> listByStatus(String status) {
        return merchantMapper.selectList(new LambdaQueryWrapper<Merchant>()
                .eq(status != null && !status.isBlank(), Merchant::getStatus, status)
                .orderByDesc(Merchant::getId));
    }

    public boolean isAdmin(Long userId) {
        User user = userMapper.selectById(userId);
        return user != null && "ADMIN".equals(user.getRole());
    }

    public void completeOrder(Long merchantId, BigDecimal score) {
        requirePositiveMerchantId(merchantId);
        creditMapper.ensure(merchantId);
        if (creditMapper.completeOrder(merchantId, normalizedScore(score)) <= 0) {
            throw new IllegalStateException("商家信用更新失败");
        }
    }

    public void refundOrder(Long merchantId) {
        requirePositiveMerchantId(merchantId);
        creditMapper.ensure(merchantId);
        if (creditMapper.refundOrder(merchantId) <= 0) {
            throw new IllegalStateException("商家信用更新失败");
        }
    }

    public void disputeOrder(Long merchantId) {
        requirePositiveMerchantId(merchantId);
        creditMapper.ensure(merchantId);
        if (creditMapper.disputeOrder(merchantId) <= 0) {
            throw new IllegalStateException("商家信用更新失败");
        }
    }

    public MerchantCredit getCredit(Long merchantId, Long userId) {
        getApprovedMerchant(merchantId, userId);
        MerchantCredit credit = creditMapper.selectById(merchantId);
        if (credit == null) {
            throw new IllegalArgumentException("商家信用数据不存在");
        }
        return credit;
    }

    private void requirePositiveMerchantId(Long merchantId) {
        if (merchantId == null || merchantId <= 0) {
            throw new IllegalArgumentException("商家ID不合法");
        }
    }

    private BigDecimal normalizedScore(BigDecimal score) {
        if (score == null || score.compareTo(BigDecimal.ONE) < 0 || score.compareTo(BigDecimal.valueOf(5)) > 0) {
            throw new IllegalArgumentException("评价分数必须在 1 到 5 之间");
        }
        return score;
    }

    public Merchant getApprovedMerchantById(Long merchantId) {
        Merchant merchant = merchantMapper.selectById(merchantId);
        if (merchant == null || !STATUS_APPROVED.equals(merchant.getStatus())
                || RISK_BLOCKED_STATUSES.contains(nullToNormal(merchant.getRiskStatus()))) {
            throw new IllegalArgumentException("商家不存在或未通过审核");
        }
        return merchant;
    }

    private MerchantDeposit findDeposit(Long merchantId) {
        return depositMapper.selectOne(new LambdaQueryWrapper<MerchantDeposit>()
                .eq(MerchantDeposit::getMerchantId, merchantId));
    }

    private void insertAudit(Long merchantId, String action, Long auditorId, String reason) {
        MerchantAudit audit = new MerchantAudit();
        audit.setMerchantId(merchantId);
        audit.setAction(action);
        audit.setAuditorId(auditorId);
        audit.setReason(reason);
        audit.setCreatedTime(LocalDateTime.now());
        auditMapper.insert(audit);
    }

    private String merchantContent(String merchantName, String contactEmail, String introduction) {
        return String.join("\n",
                merchantName == null ? "" : merchantName,
                contactEmail == null ? "" : contactEmail,
                introduction == null ? "" : introduction);
    }

    private void requireLocalSafeMerchant(List<SensitiveWordHitDTO> hits) {
        boolean unsafe = hits.stream().map(SensitiveWordHitDTO::getCategory).anyMatch(category ->
                "ILLEGAL_ASSET".equals(category) || "OFF_PLATFORM_CONTACT".equals(category)
                        || "PRIVATE_TRANSACTION".equals(category));
        if (unsafe) {
            throw new IllegalArgumentException("商家申请包含禁售或站外交易信息");
        }
    }

    private Map<String, Object> merchantPayload(String content, List<SensitiveWordHitDTO> hits) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("contentHash", RiskSupport.sha256(content));
        payload.put("contentLength", content.length());
        return payload;
    }

    private String nullToNormal(String value) {
        return value == null || value.isBlank() ? "NORMAL" : value;
    }
}
