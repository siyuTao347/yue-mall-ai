package com.example.item.service;

import api.risk.RiskCommandDTO;
import com.example.item.mapper.TradeOrderMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;

@Service
public class OrderRiskCommandHandler {
    private static final Set<String> SUPPORTED_SCENES =
            Set.of("ORDER", "PAYMENT", "DELIVERY", "CONFIRM", "DISPUTE");
    private static final Set<String> SUPPORTED_COMMANDS =
            Set.of("APPROVE", "FREEZE", "LIMIT", "DELAY_SETTLE");

    private final TradeOrderMapper orderMapper;

    public OrderRiskCommandHandler(TradeOrderMapper orderMapper) {
        this.orderMapper = orderMapper;
    }

    public boolean handle(RiskCommandDTO command) {
        if (!SUPPORTED_SCENES.contains(command.getScene())) {
            return false;
        }
        String orderNo = command.getOrderNo() == null || command.getOrderNo().isBlank()
                ? command.getBizNo() : command.getOrderNo();
        if (orderNo == null || orderNo.isBlank()) {
            throw new IllegalArgumentException("订单风控命令缺少订单编号");
        }
        if (!SUPPORTED_COMMANDS.contains(command.getCommand())) {
            throw new IllegalArgumentException("订单风控命令不支持: " + command.getCommand());
        }
        String riskLevel = riskLevel(command);
        String reason = reason(command);
        LocalDateTime now = LocalDateTime.now();
        int updated = switch (command.getCommand()) {
            case "APPROVE" -> orderMapper.updateRiskStatusIfAllowed(orderNo, "NORMAL", riskLevel,
                    command.getDecisionNo(), reason, now);
            case "FREEZE" -> orderMapper.updateRiskStatusIfAllowed(orderNo, "FROZEN", riskLevel,
                    command.getDecisionNo(), reason, now);
            case "LIMIT" -> orderMapper.updateRiskStatusIfAllowed(orderNo, "LIMITED", riskLevel,
                    command.getDecisionNo(), reason, now);
            case "DELAY_SETTLE" -> orderMapper.delaySettlement(orderNo,
                    now.plusHours(delayHours(command)), now);
            default -> 0;
        };
        return updated > 0;
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
                throw new IllegalArgumentException("订单风控延迟小时数不合法", e);
            }
        }
        return 24;
    }

    private int validateHours(int hours) {
        if (hours <= 0 || hours > 720) {
            throw new IllegalArgumentException("订单风控延迟小时数必须在 1 到 720 之间");
        }
        return hours;
    }

    private String reason(RiskCommandDTO command) {
        return command.getReason() == null || command.getReason().isBlank()
                ? "风控案件人工处理" : command.getReason();
    }
}
