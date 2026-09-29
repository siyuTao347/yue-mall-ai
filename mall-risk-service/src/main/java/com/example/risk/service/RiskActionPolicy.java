package com.example.risk.service;

import api.risk.RiskDecisionResult;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

@Service
public class RiskActionPolicy {
    private static final List<String> PRIORITY = List.of(
            RiskDecisionResult.ACTION_REJECT,
            RiskDecisionResult.ACTION_FREEZE,
            RiskDecisionResult.ACTION_MANUAL_REVIEW,
            RiskDecisionResult.ACTION_LIMIT,
            RiskDecisionResult.ACTION_VERIFY,
            RiskDecisionResult.ACTION_WATCH,
            RiskDecisionResult.ACTION_PASS
    );

    public String highestAction(List<String> actions) {
        return actions.stream()
                .filter(PRIORITY::contains)
                .min(Comparator.comparingInt(PRIORITY::indexOf))
                .orElse(RiskDecisionResult.ACTION_PASS);
    }

    public String upgradeByScore(int score, String action) {
        if (score >= 80 && PRIORITY.indexOf(action) > PRIORITY.indexOf(RiskDecisionResult.ACTION_MANUAL_REVIEW)) {
            return RiskDecisionResult.ACTION_MANUAL_REVIEW;
        }
        return action;
    }

    public String level(int score) {
        if (score >= 90) {
            return "CRITICAL";
        }
        if (score >= 70) {
            return "HIGH";
        }
        if (score >= 40) {
            return "MEDIUM";
        }
        return "LOW";
    }
}
