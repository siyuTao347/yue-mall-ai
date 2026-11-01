package com.example.moneyagent.investigation;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

@Component
public class MoneyPlanValidator {

    private static final Set<String> ALLOWED_TOOLS = Set.of(
            "OBSERVE_CHANNEL", "QUERY_FUND", "QUERY_ORDER_SNAPSHOT", "REQUEST_INPUT"
    );

    public record ValidationResult(boolean valid, String rejectReason) {}

    public ValidationResult validatePlan(ModelPlanResult plan, String authorizedScopeKey,
                                         String authorizedTargetRef, List<String> validFactIds) {
        if (plan == null) {
            return new ValidationResult(false, "规划结果为空");
        }
        if (!plan.isActionNeeded()) {
            return new ValidationResult(true, null);
        }

        // 1. 工具白名单校验
        if (plan.getToolName() == null || !ALLOWED_TOOLS.contains(plan.getToolName().trim())) {
            return new ValidationResult(false, "规划工具不在受信任目录中: " + plan.getToolName());
        }

        // 2. 目标引用与授权范围校验
        String target = plan.getTargetRef();
        if (target != null && !target.isBlank()) {
            boolean matchesScope = authorizedScopeKey != null && authorizedScopeKey.contains(target);
            boolean matchesTarget = authorizedTargetRef != null && authorizedTargetRef.equalsIgnoreCase(target);
            if (!matchesScope && !matchesTarget) {
                return new ValidationResult(false, "规划动作目标超出授权范围: " + target);
            }
        }

        // 3. 支撑事实 ID 校验
        if (plan.getSupportingFactIds() != null && !plan.getSupportingFactIds().isEmpty()) {
            for (String factId : plan.getSupportingFactIds()) {
                if (validFactIds != null && !validFactIds.contains(factId)) {
                    return new ValidationResult(false, "规划动作引用了未验证的事实 ID: " + factId);
                }
            }
        }

        return new ValidationResult(true, null);
    }
}

