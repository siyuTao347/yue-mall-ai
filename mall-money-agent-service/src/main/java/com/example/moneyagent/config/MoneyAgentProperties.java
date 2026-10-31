package com.example.moneyagent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "money-agent")
public class MoneyAgentProperties {
    private boolean enabled = true;
    private boolean acceptNewCases = true;
    private boolean workerEnabled = false;
    private String modelMode = "MOCK";
    private int maxPlanRounds = 3;
    private int maxToolCalls = 10;
    private int maxSourceRpcCalls = 30;
    private int maxPagesPerSource = 5;
    private int taskLeaseSeconds = 30;
    private int taskHeartbeatSeconds = 5;
    private int workerConcurrency = 4;
    private long modelTimeoutMs = 15000L;
    private String a2aPath = "/internal/a2a/money";
    private String sourceTimeZone = "Asia/Shanghai";
    private boolean repairEnabled = false;
}

