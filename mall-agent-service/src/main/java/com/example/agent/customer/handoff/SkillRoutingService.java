package com.example.agent.customer.handoff;

import com.example.agent.customer.CustomerDatabase;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

import static com.example.agent.customer.CustomerJson.read;

@Service
public class SkillRoutingService {

    private final CustomerDatabase db;

    public SkillRoutingService(CustomerDatabase db) {
        this.db = db;
    }

    public record RoutingDecision(String targetGroup, int priorityRank, String ruleCode, int ruleVersion) {}

    public RoutingDecision route(String riskLevel, String primaryIntent, String question) {
        // 1. 最高优先级：URGENT 必须进入 SENIOR_SUPPORT
        if ("URGENT".equalsIgnoreCase(riskLevel) || (question != null && question.matches("(?s).*(报警|诈骗|起诉|立案).*"))) {
            return new RoutingDecision("SENIOR_SUPPORT", 10, "RULE_URGENT_ESCALATE", 1);
        }

        // 2. 高风险 / 资金损失 / 赔偿类必须进入 SENIOR_SUPPORT
        if ("HIGH".equalsIgnoreCase(riskLevel) || (question != null && question.matches("(?s).*(被盗|资金损失|赔偿|仲裁).*"))) {
            return new RoutingDecision("SENIOR_SUPPORT", 20, "RULE_HIGH_RISK_CLAIM", 1);
        }

        // 3. 售后类型进入 AFTER_SALE
        if (primaryIntent != null && primaryIntent.startsWith("AFTER_SALE")) {
            return new RoutingDecision("AFTER_SALE", 30, "RULE_AFTER_SALE_ROUTE", 1);
        }

        // 4. 售前导购进入 SALES
        if (primaryIntent != null && (primaryIntent.startsWith("PRE_SALE") || "ITEM_RECOMMENDATION".equals(primaryIntent))) {
            return new RoutingDecision("SALES", 40, "RULE_PRE_SALE_ROUTE", 1);
        }

        // 5. 兜底进入 GENERAL_SUPPORT
        return new RoutingDecision("GENERAL_SUPPORT", 100, "RULE_DEFAULT_FALLBACK", 1);
    }

    public Long getGroupIdByCode(String code) {
        List<Long> ids = db.mysql.queryForList(
                "SELECT group_id FROM t_agent_skill_group WHERE code = ? AND enabled = 1",
                Long.class, code
        );
        return ids.isEmpty() ? 4L : ids.getFirst();
    }
}
