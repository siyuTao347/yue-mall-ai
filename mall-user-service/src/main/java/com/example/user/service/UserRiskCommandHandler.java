package com.example.user.service;

import api.risk.RiskCommandDTO;
import com.example.user.entity.MerchantAudit;
import com.example.user.mapper.MerchantAuditMapper;
import com.example.user.mapper.MerchantMapper;
import com.example.user.mapper.WithdrawRequestMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;

@Service
public class UserRiskCommandHandler {
    private static final Set<String> SUPPORTED_SCENES = Set.of("MERCHANT", "WITHDRAW");
    private static final Set<String> MERCHANT_COMMANDS =
            Set.of("APPROVE", "REJECT", "FREEZE", "LIMIT");
    private static final Set<String> WITHDRAW_COMMANDS =
            Set.of("APPROVE", "REJECT", "FREEZE", "LIMIT", "DELAY_SETTLE");

    private final MerchantMapper merchantMapper;
    private final MerchantAuditMapper auditMapper;
    private final WithdrawRequestMapper withdrawMapper;
    private final WithdrawService withdrawService;

    public UserRiskCommandHandler(MerchantMapper merchantMapper, MerchantAuditMapper auditMapper,
                                  WithdrawRequestMapper withdrawMapper, WithdrawService withdrawService) {
        this.merchantMapper = merchantMapper;
        this.auditMapper = auditMapper;
        this.withdrawMapper = withdrawMapper;
        this.withdrawService = withdrawService;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean handle(RiskCommandDTO command) {
        if (!SUPPORTED_SCENES.contains(command.getScene())) {
            return false;
        }
        return "MERCHANT".equals(command.getScene()) ? handleMerchant(command) : handleWithdraw(command);
    }

    private boolean handleMerchant(RiskCommandDTO command) {
        Long merchantId = command.getMerchantId() == null ? parseLong(command.getBizNo())
                : command.getMerchantId();
        if (merchantId == null) {
            throw new IllegalArgumentException("商家风控命令缺少商家编号");
        }
        if (!MERCHANT_COMMANDS.contains(command.getCommand())) {
            throw new IllegalArgumentException("商家风控命令不支持: " + command.getCommand());
        }
        String riskLevel = riskLevel(command);
        String reason = reason(command);
        LocalDateTime now = LocalDateTime.now();
        int updated = switch (command.getCommand()) {
            case "APPROVE" -> merchantMapper.updateRiskStatusIfAllowed(merchantId, "NORMAL",
                    riskLevel, command.getDecisionNo(), reason, now);
            case "REJECT" -> merchantMapper.rejectByRisk(merchantId, riskLevel,
                    command.getDecisionNo(), reason, now);
            case "FREEZE" -> merchantMapper.freezeByRisk(merchantId, riskLevel,
                    command.getDecisionNo(), reason, now);
            case "LIMIT" -> merchantMapper.updateRiskStatusIfAllowed(merchantId, "LIMITED",
                    riskLevel, command.getDecisionNo(), reason, now);
            default -> 0;
        };
        if (updated > 0) {
            insertMerchantAudit(merchantId, "RISK_" + command.getCommand(),
                    command.getOperatorId(), reason);
        }
        return updated > 0;
    }

    private boolean handleWithdraw(RiskCommandDTO command) {
        String withdrawNo = command.getWithdrawNo() == null || command.getWithdrawNo().isBlank()
                ? command.getBizNo() : command.getWithdrawNo();
        if (withdrawNo == null || withdrawNo.isBlank()) {
            throw new IllegalArgumentException("提现风控命令缺少提现单号");
        }
        if (!WITHDRAW_COMMANDS.contains(command.getCommand())) {
            throw new IllegalArgumentException("提现风控命令不支持: " + command.getCommand());
        }
        if ("REJECT".equals(command.getCommand())) {
            return withdrawService.rejectByRisk(command);
        }
        String riskLevel = riskLevel(command);
        String reason = reason(command);
        LocalDateTime now = LocalDateTime.now();
        int updated = switch (command.getCommand()) {
            case "APPROVE" -> withdrawMapper.updateRiskStatusIfAllowed(withdrawNo, "NORMAL",
                    riskLevel, command.getDecisionNo(), reason, now);
            case "FREEZE" -> withdrawMapper.updateRiskStatusIfAllowed(withdrawNo, "FROZEN",
                    riskLevel, command.getDecisionNo(), reason, now);
            case "LIMIT" -> withdrawMapper.updateRiskStatusIfAllowed(withdrawNo, "LIMITED",
                    riskLevel, command.getDecisionNo(), reason, now);
            case "DELAY_SETTLE" -> withdrawMapper.delayPayout(withdrawNo,
                    now.plusHours(delayHours(command)), now);
            default -> 0;
        };
        return updated > 0;
    }

    private void insertMerchantAudit(Long merchantId, String action, Long operatorId, String reason) {
        MerchantAudit audit = new MerchantAudit();
        audit.setMerchantId(merchantId);
        audit.setAction(action);
        audit.setAuditorId(operatorId);
        audit.setReason(reason);
        audit.setCreatedTime(LocalDateTime.now());
        auditMapper.insert(audit);
    }

    private Long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("商家风控命令业务编号不合法", e);
        }
    }

    private String riskLevel(RiskCommandDTO command) {
        Map<String, Object> params = command.getActionParams();
        Object value = params == null ? null : params.get("riskLevel");
        String level = value == null ? null : String.valueOf(value);
        return level == null || level.isBlank() ? defaultRiskLevel(command.getCommand()) : level;
    }

    private String defaultRiskLevel(String command) {
        return switch (command) {
            case "APPROVE" -> "LOW";
            case "LIMIT", "DELAY_SETTLE" -> "MEDIUM";
            default -> "HIGH";
        };
    }

    private int delayHours(RiskCommandDTO command) {
        Map<String, Object> params = command.getActionParams();
        Object value = params == null ? null : params.get("delayHours");
        if (value instanceof Number number) {
            return validateHours(number.intValue());
        }
        if (value != null) {
            try {
                return validateHours(Integer.parseInt(String.valueOf(value)));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("提现风控延迟小时数不合法", e);
            }
        }
        return 24;
    }

    private int validateHours(int hours) {
        if (hours <= 0 || hours > 720) {
            throw new IllegalArgumentException("提现风控延迟小时数必须在 1 到 720 之间");
        }
        return hours;
    }

    private String reason(RiskCommandDTO command) {
        return command.getReason() == null || command.getReason().isBlank()
                ? "风控案件人工处理" : command.getReason();
    }
}
