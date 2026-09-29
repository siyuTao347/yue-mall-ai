package com.example.risk.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.risk.entity.RiskRule;
import com.example.risk.mapper.RiskRuleMapper;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Service
public class RiskRuleCache {
    private final RiskRuleMapper ruleMapper;
    private volatile List<RiskRule> rules = List.of();
    private volatile Instant loadedAt = Instant.EPOCH;

    public RiskRuleCache(RiskRuleMapper ruleMapper) {
        this.ruleMapper = ruleMapper;
    }

    public List<RiskRule> enabledRules(String scene) {
        if (Duration.between(loadedAt, Instant.now()).compareTo(Duration.ofSeconds(30)) > 0) {
            rules = ruleMapper.selectList(new LambdaQueryWrapper<RiskRule>()
                    .eq(RiskRule::getEnabled, true)
                    .orderByAsc(RiskRule::getId));
            loadedAt = Instant.now();
        }
        return rules.stream()
                .filter(rule -> scene.equals(rule.getScene()))
                .limit(100)
                .toList();
    }

    public void evict() {
        loadedAt = Instant.EPOCH;
        rules = List.of();
    }
}
