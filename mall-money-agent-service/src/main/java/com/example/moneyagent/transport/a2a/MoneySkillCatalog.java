package com.example.moneyagent.transport.a2a;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 资金 Agent 能力卡 (Agent Card) 与已发布技能目录
 * <p>
 * 提供 /.well-known/agent-card.json 发现能力与技能元数据
 * </p>
 */
@Component
public class MoneySkillCatalog {

    public Map<String, Object> getAgentCard() {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("schemaVersion", "1.0.0");
        card.put("name", "Money Error Agent");
        card.put("description", "资金异常自动排查、跨渠道核对与多方台账核实智能体");
        card.put("version", "1.0.0");
        card.put("endpoint", "/internal/a2a/money");

        List<Map<String, Object>> skills = List.of(
                Map.of(
                        "id", "money.investigate.v1",
                        "name", "发起资金调查",
                        "description", "受理客户或财务发起的资金异常排查请求并启动调查工作流",
                        "inputSchema", "mall.money.investigation-request.v1"
                ),
                Map.of(
                        "id", "money.case-status.v1",
                        "name", "查询案件状态",
                        "description", "即时查询指定资金案件的受众视图与最新进展",
                        "inputSchema", "money.case-status.v1"
                ),
                Map.of(
                        "id", "money.provide-input.v1",
                        "name", "补充调查材料",
                        "description", "为正在等待输入的调查任务提交补充材料",
                        "inputSchema", "mall.money.additional-input.v1"
                ),
                Map.of(
                        "id", "money.cancel-task.v1",
                        "name", "取消调查任务",
                        "description", "受控取消正在进行中的资金调查工作",
                        "inputSchema", "mall.money.control.v1"
                )
        );
        card.put("skills", skills);

        card.put("capabilities", Map.of(
                "streaming", false,
                "pushNotifications", false
        ));

        card.put("authentication", Map.of(
                "type", "DUAL_HEADER_JOSE",
                "headers", List.of("Authorization", "X-Money-Delegation")
        ));

        return card;
    }
}

