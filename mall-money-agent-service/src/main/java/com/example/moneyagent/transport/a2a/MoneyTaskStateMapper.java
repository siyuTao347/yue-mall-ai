package com.example.moneyagent.transport.a2a;

import org.springframework.stereotype.Component;

/**
 * 内部任务状态与 A2A 协议任务状态映射器
 */
@Component
public class MoneyTaskStateMapper {

    public String toA2aState(String internalState) {
        if (internalState == null) return "working";
        return switch (internalState.toUpperCase()) {
            case "SUBMITTED" -> "submitted";
            case "WORKING" -> "working";
            case "COMPLETED" -> "completed";
            case "FAILED" -> "failed";
            case "CANCELED" -> "canceled";
            case "INPUT_REQUIRED" -> "input_required";
            case "AUTH_REQUIRED" -> "auth_required";
            default -> "working";
        };
    }
}

