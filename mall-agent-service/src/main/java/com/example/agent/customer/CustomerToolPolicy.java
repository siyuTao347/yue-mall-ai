package com.example.agent.customer;

import java.util.List;
import java.util.Map;
import java.util.Set;
import static com.example.agent.customer.CustomerJson.error;

/** Server-side capabilities; neither prompts nor tool arguments can widen these sets. */
public final class CustomerToolPolicy {
    private CustomerToolPolicy() {}
    private static final Map<String, Set<String>> TOOLS = Map.of(
        "MASTER_AGENT", Set.of("queryMyOrders"),
        "ORDER_AGENT", Set.of("queryMyOrders", "queryOrder", "queryOrderTimeline", "queryDeliveryEvidenceSummary"),
        "AFTER_SALE_AGENT", Set.of("queryOrder", "queryDispute", "queryDeliveryEvidenceSummary", "searchKnowledge"),
        "RULE_AGENT", Set.of("searchKnowledge"),
        "PRE_SALE_AGENT", Set.of("queryPublicItem", "searchKnowledge"),
        "COMPLAINT_AGENT", Set.of("createHandoff", "queryOrder", "queryDispute"));

    public static void require(String agent, String tool) {
        if (!TOOLS.getOrDefault(agent, Set.of()).contains(tool))
            throw error(403, "AGENT_TOOL_NOT_ALLOWED", "此节点不允许调用该工具");
    }

    public static List<String> documentTypes(String agent, List<String> requested) {
        require(agent, "searchKnowledge");
        List<String> allowed = switch (agent) {
            case "AFTER_SALE_AGENT" -> List.of("AFTER_SALE", "RULE", "FAQ");
            case "PRE_SALE_AGENT" -> List.of("PRODUCT", "RULE", "FAQ");
            default -> List.of("RULE", "POLICY", "FAQ");
        };
        if (requested == null || requested.isEmpty()) return allowed;
        if (!allowed.containsAll(requested)) throw error(403, "AGENT_TOOL_NOT_ALLOWED", "此节点不允许检索该类资料");
        return List.copyOf(requested);
    }
}
