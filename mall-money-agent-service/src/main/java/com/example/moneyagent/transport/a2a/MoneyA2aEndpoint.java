package com.example.moneyagent.transport.a2a;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 资金 Agent A2A 协议端点
 * <p>
 * 提供：
 * 1. JSON-RPC 2.0 端点 (/internal/a2a/money, /a2a/money)
 * 2. 能力发现 Agent Card 端点 (/.well-known/agent-card.json)
 * </p>
 */
@Slf4j
@RestController
public class MoneyA2aEndpoint {

    private final MoneyA2aAdapter a2aAdapter;
    private final MoneySkillCatalog skillCatalog;

    public MoneyA2aEndpoint(MoneyA2aAdapter a2aAdapter, MoneySkillCatalog skillCatalog) {
        this.a2aAdapter = a2aAdapter;
        this.skillCatalog = skillCatalog;
    }

    @PostMapping({"${money-agent.a2a-path:/internal/a2a/money}", "/internal/a2a/money", "/a2a/money"})
    public Map<String, Object> handleJsonRpc(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authHeader,
            @RequestHeader(value = "X-Money-Delegation", required = false) String delegationHeader) {
        return a2aAdapter.handleRpc(body, authHeader, delegationHeader);
    }

    @GetMapping("/.well-known/agent-card.json")
    public Map<String, Object> getAgentCard() {
        return skillCatalog.getAgentCard();
    }
}

