package com.example.item.service;

import api.risk.RiskCommandDTO;
import com.example.item.mapper.ItemMapper;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;

@Service
public class ItemRiskCommandHandler {
    private static final Set<String> SUPPORTED_COMMANDS = Set.of("APPROVE", "REJECT", "FREEZE");

    private final ItemMapper itemMapper;

    public ItemRiskCommandHandler(ItemMapper itemMapper) {
        this.itemMapper = itemMapper;
    }

    public boolean handle(RiskCommandDTO command) {
        if (!"LISTING".equals(command.getScene())) {
            return false;
        }
        Long itemId = command.getItemId() == null ? parseBizNo(command.getBizNo()) : command.getItemId();
        if (itemId == null) {
            throw new IllegalArgumentException("商品风控命令缺少商品编号");
        }
        if (!SUPPORTED_COMMANDS.contains(command.getCommand())) {
            throw new IllegalArgumentException("商品风控命令不支持: " + command.getCommand());
        }
        String riskLevel = riskLevel(command);
        String reason = reason(command);
        int updated = switch (command.getCommand()) {
            case "APPROVE" -> itemMapper.updateRiskStatusIfAllowed(itemId, "NORMAL", riskLevel,
                    command.getDecisionNo(), reason);
            case "REJECT" -> itemMapper.rejectByRisk(itemId, riskLevel, command.getDecisionNo(), reason);
            case "FREEZE" -> itemMapper.freezeByRisk(itemId, riskLevel, command.getDecisionNo(), reason);
            default -> 0;
        };
        return updated > 0;
    }

    private Long parseBizNo(String bizNo) {
        if (bizNo == null || bizNo.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(bizNo);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("商品风控命令业务编号不合法", e);
        }
    }

    private String riskLevel(RiskCommandDTO command) {
        Map<String, Object> params = command.getActionParams();
        Object value = params == null ? null : params.get("riskLevel");
        String level = value == null ? null : String.valueOf(value);
        return level == null || level.isBlank() ? defaultRiskLevel(command.getCommand()) : level;
    }

    private String defaultRiskLevel(String command) {
        return "APPROVE".equals(command) ? "LOW" : "HIGH";
    }

    private String reason(RiskCommandDTO command) {
        return command.getReason() == null || command.getReason().isBlank()
                ? "风控案件人工处理" : command.getReason();
    }
}
