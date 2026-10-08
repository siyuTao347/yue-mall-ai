package com.example.risk.service;

import api.risk.RiskDecisionResult;
import api.risk.RiskEvaluateRequest;
import api.risk.RiskRuleHitDTO;
import api.risk.RiskSupport;
import api.risk.SensitiveWordHitDTO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.risk.entity.RiskDecision;
import com.example.risk.entity.RiskEvent;
import com.example.risk.mapper.RiskDecisionMapper;
import com.example.risk.mapper.RiskEventMapper;
import com.example.risk.mapper.RiskSubjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

@Service
@Slf4j
public class RiskEvaluateService {
    private final RiskEventMapper eventMapper;
    private final RiskDecisionMapper decisionMapper;
    private final RiskSubjectMapper subjectMapper;
    private final RiskRuleCache ruleCache;
    private final RiskExpressionService expressionService;
    private final RiskMetricService metricService;
    private final RiskActionPolicy actionPolicy;
    private final RiskIdentityService identityService;
    private final RiskCaseService caseService;
    private final RiskIndicatorService indicatorService;
    private final ObjectMapper objectMapper;

    public RiskEvaluateService(RiskEventMapper eventMapper, RiskDecisionMapper decisionMapper,
                               RiskSubjectMapper subjectMapper, RiskRuleCache ruleCache,
                               RiskExpressionService expressionService, RiskMetricService metricService,
                               RiskActionPolicy actionPolicy, RiskIdentityService identityService,
                               RiskCaseService caseService, RiskIndicatorService indicatorService,
                               ObjectMapper objectMapper) {
        this.eventMapper = eventMapper;
        this.decisionMapper = decisionMapper;
        this.subjectMapper = subjectMapper;
        this.ruleCache = ruleCache;
        this.expressionService = expressionService;
        this.metricService = metricService;
        this.actionPolicy = actionPolicy;
        this.identityService = identityService;
        this.caseService = caseService;
        this.indicatorService = indicatorService;
        this.objectMapper = objectMapper;
    }

    @Transactional(rollbackFor = Exception.class)
    public RiskDecisionResult evaluate(RiskEvaluateRequest request) {
        return process(request, false);
    }

    @Transactional(rollbackFor = Exception.class)
    public RiskDecisionResult recordEvent(RiskEvaluateRequest request) {
        return process(request, true);
    }

    @Transactional(rollbackFor = Exception.class)
    public void confirmEvent(String eventNo) {
        if (eventNo == null || eventNo.isBlank()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        if (eventMapper.confirmEvent(eventNo, now) <= 0) {
            return;
        }
        RiskEvent event = findEvent(eventNo);
        if (event != null) {
            identityService.applyConfirmedEvent(event);
            indicatorService.enqueueEvent(event);
        }
    }

    private RiskDecisionResult process(RiskEvaluateRequest request, boolean confirmed) {
        validate(request);
        String requestHash = requestHash(request);
        RiskEvent existing = findEvent(request.getEventNo());
        if (existing != null) {
            if (!requestHash.equals(existing.getRequestHash())) {
                return RiskDecisionResult.conflict(request.getEventNo());
            }
            RiskDecision decision = findDecision(request.getEventNo());
            if (decision == null) {
                throw new IllegalStateException("风控事件缺少决策记录: " + request.getEventNo());
            }
            return resultOf(decision);
        }

        RiskEvent event = buildEvent(request, requestHash, confirmed);
        try {
            eventMapper.insert(event);
        } catch (DuplicateKeyException e) {
            RiskEvent winner = findEvent(request.getEventNo());
            if (winner == null || !requestHash.equals(winner.getRequestHash())) {
                return RiskDecisionResult.conflict(request.getEventNo());
            }
            RiskDecision decision = findDecision(request.getEventNo());
            if (decision == null) {
                throw new IllegalStateException("风控事件缺少决策记录: " + request.getEventNo());
            }
            return resultOf(decision);
        }
        if (confirmed) {
            identityService.applyConfirmedEvent(event);
            indicatorService.enqueueEvent(event);
        }
        return decide(event);
    }

    private RiskDecisionResult decide(RiskEvent event) {
        RiskEvaluateRequest request = requestOf(event);
        RiskMetricService.Metrics metrics = metricService.calculate(request);
        List<RiskRuleHitDTO> hits = new ArrayList<>();
        List<String> actions = new ArrayList<>();
        Set<String> missingMetrics = new LinkedHashSet<>();
        int score = 0;
        for (com.example.risk.entity.RiskRule rule : ruleCache.enabledRules(event.getScene())) {
            try {
                RiskExpressionService.Evaluation evaluation =
                        expressionService.evaluate(rule.getExpressionJson(), metrics.values());
                missingMetrics.addAll(evaluation.missingMetrics());
                if (!evaluation.matched()) {
                    continue;
                }
                hits.add(RiskRuleHitDTO.builder()
                        .ruleCode(rule.getRuleCode())
                        .ruleName(rule.getRuleName())
                        .action(rule.getAction())
                        .riskScore(rule.getRiskScore())
                        .autoCase(rule.getAutoCase())
                        .actualValues(evaluation.actualValues())
                        .build());
                actions.add(rule.getAction());
                score += rule.getRiskScore() == null ? 0 : rule.getRiskScore();
            } catch (IllegalArgumentException e) {
                log.warn("非法风控规则不参与执行, ruleCode={}, error={}", rule.getRuleCode(), e.getMessage());
            }
        }
        score = Math.min(100, Math.max(0, score));
        String action = actionPolicy.upgradeByScore(score, actionPolicy.highestAction(actions));
        String riskLevel = actionPolicy.level(score);
        String decisionNo = "RD-" + event.getScene() + "-" + System.currentTimeMillis() + "-"
                + UUID.randomUUID().toString().replace("-", "");

        RiskDecision decision = new RiskDecision();
        decision.setDecisionNo(decisionNo);
        decision.setEventNo(event.getEventNo());
        decision.setScene(event.getScene());
        decision.setBizType(event.getBizType());
        decision.setBizNo(event.getBizNo());
        decision.setAction(action);
        decision.setRiskScore(score);
        decision.setRiskLevel(riskLevel);
        decision.setHitRulesJson(writeJson(hits));
        decision.setMetricSnapshotJson(writeJson(metrics.values()));
        decision.setEvidenceJson(evidenceJson(event));
        decision.setMissingMetricsJson(writeJson(List.copyOf(missingMetrics)));
        decision.setDegraded(false);
        decisionMapper.insert(decision);

        if (!RiskDecisionResult.ACTION_PASS.equals(action)) {
            upsertSubject(event, decisionNo, action, riskLevel, score, hits);
        }
        caseService.createAutoCase(event, decisionNo, action, riskLevel, score, hits);
        return RiskDecisionResult.builder()
                .eventNo(event.getEventNo())
                .decisionNo(decisionNo)
                .action(action)
                .riskScore(score)
                .riskLevel(riskLevel)
                .degraded(false)
                .message(safeMessage(action))
                .hitRules(hits)
                .build();
    }

    private RiskEvent buildEvent(RiskEvaluateRequest request, String requestHash, boolean confirmed) {
        LocalDateTime now = LocalDateTime.now();
        RiskEvent event = new RiskEvent();
        event.setEventNo(request.getEventNo());
        event.setScene(request.getScene());
        event.setEventType(request.getEventType());
        event.setEventPhase(confirmed ? "CONFIRMED" : "PRECHECK");
        event.setBizType(request.getBizType());
        event.setBizNo(request.getBizNo());
        event.setUserId(request.getUserId());
        event.setMerchantId(request.getMerchantId());
        event.setItemId(request.getItemId());
        event.setOrderNo(request.getOrderNo());
        event.setWithdrawNo(request.getWithdrawNo());
        event.setAmount(request.getAmount());
        event.setIpHash(request.getIpHash());
        event.setDeviceHash(request.getDeviceHash());
        event.setPayloadJson(payloadJson(request));
        event.setRequestHash(requestHash);
        event.setOccurredTime(now);
        event.setConfirmedTime(confirmed ? now : null);
        event.setCreatedTime(now);
        event.setUpdatedTime(now);
        return event;
    }

    private RiskEvaluateRequest requestOf(RiskEvent event) {
        Map<String, Object> payload = payload(event.getPayloadJson());
        Object sensitiveHits = payload.remove("sensitiveHits");
        List<SensitiveWordHitDTO> hits = sensitiveHits == null ? List.of()
                : objectMapper.convertValue(sensitiveHits, new TypeReference<List<SensitiveWordHitDTO>>() {
                });
        return RiskEvaluateRequest.builder()
                .eventNo(event.getEventNo())
                .scene(event.getScene())
                .eventType(event.getEventType())
                .bizType(event.getBizType())
                .bizNo(event.getBizNo())
                .userId(event.getUserId())
                .merchantId(event.getMerchantId())
                .itemId(event.getItemId())
                .orderNo(event.getOrderNo())
                .withdrawNo(event.getWithdrawNo())
                .amount(event.getAmount())
                .ipHash(event.getIpHash())
                .deviceHash(event.getDeviceHash())
                .payload(payload)
                .sensitiveHits(hits)
                .build();
    }

    private void upsertSubject(RiskEvent event, String decisionNo, String action, String riskLevel,
                               int score, List<RiskRuleHitDTO> hits) {
        Subject subject = subjectOf(event);
        String reason = hits.stream()
                .map(RiskRuleHitDTO::getRuleCode)
                .limit(3)
                .reduce((left, right) -> left + "," + right)
                .orElse("风控决策");
        subjectMapper.upsert(subject.type(), subject.id(), RiskSupport.toRiskStatus(action),
                riskLevel, score, decisionNo, reason);
    }

    private Subject subjectOf(RiskEvent event) {
        Map<String, Object> payload = payload(event.getPayloadJson());
        Long withdrawId = asLong(payload.get("withdrawId"));
        if ("WITHDRAW".equals(event.getScene()) && withdrawId != null) {
            return new Subject("WITHDRAW", withdrawId);
        }
        if ("ITEM".equalsIgnoreCase(event.getBizType()) && event.getItemId() != null) {
            return new Subject("ITEM", event.getItemId());
        }
        if (("MERCHANT".equals(event.getScene()) || "MERCHANT".equalsIgnoreCase(event.getBizType()))
                && event.getMerchantId() != null) {
            return new Subject("MERCHANT", event.getMerchantId());
        }
        if (event.getUserId() != null) {
            return new Subject("USER", event.getUserId());
        }
        if (event.getMerchantId() != null) {
            return new Subject("MERCHANT", event.getMerchantId());
        }
        return new Subject("USER", 0L);
    }

    private RiskDecisionResult resultOf(RiskDecision decision) {
        List<RiskRuleHitDTO> hits = readHits(decision.getHitRulesJson());
        return RiskDecisionResult.builder()
                .eventNo(decision.getEventNo())
                .decisionNo(decision.getDecisionNo())
                .action(decision.getAction())
                .riskScore(decision.getRiskScore())
                .riskLevel(decision.getRiskLevel())
                .degraded(Boolean.TRUE.equals(decision.getDegraded()))
                .message(safeMessage(decision.getAction()))
                .hitRules(hits)
                .build();
    }

    private RiskEvent findEvent(String eventNo) {
        return eventMapper.selectOne(new LambdaQueryWrapper<RiskEvent>()
                .eq(RiskEvent::getEventNo, eventNo));
    }

    private RiskDecision findDecision(String eventNo) {
        return decisionMapper.selectOne(new LambdaQueryWrapper<RiskDecision>()
                .eq(RiskDecision::getEventNo, eventNo));
    }

    private void validate(RiskEvaluateRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("风控请求不能为空");
        }
        requireText(request.getEventNo(), "风控事件号不能为空");
        requireText(request.getScene(), "风控场景不能为空");
        requireText(request.getEventType(), "风控事件类型不能为空");
    }

    private void requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
    }

    private String requestHash(RiskEvaluateRequest request) {
        Map<String, Object> canonical = new TreeMap<>();
        canonical.put("eventNo", request.getEventNo());
        canonical.put("scene", request.getScene());
        canonical.put("eventType", request.getEventType());
        canonical.put("bizType", request.getBizType());
        canonical.put("bizNo", request.getBizNo());
        canonical.put("userId", request.getUserId());
        canonical.put("merchantId", request.getMerchantId());
        canonical.put("itemId", request.getItemId());
        canonical.put("orderNo", request.getOrderNo());
        canonical.put("withdrawNo", request.getWithdrawNo());
        canonical.put("amount", request.getAmount());
        canonical.put("ipHash", request.getIpHash());
        canonical.put("deviceHash", request.getDeviceHash());
        canonical.put("payload", request.getPayload() == null ? Map.of() : new TreeMap<>(request.getPayload()));
        canonical.put("sensitiveHits", request.getSensitiveHits() == null ? List.of()
                : objectMapper.convertValue(request.getSensitiveHits(),
                new TypeReference<List<Map<String, Object>>>() {
                }));
        return RiskSupport.sha256(writeJson(canonical));
    }

    private String payloadJson(RiskEvaluateRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (request.getPayload() != null) {
            payload.putAll(request.getPayload());
        }
        if (request.getSensitiveHits() != null) {
            payload.put("sensitiveHits", request.getSensitiveHits());
        }
        return writeJson(payload);
    }

    private String evidenceJson(RiskEvent event) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("eventNo", event.getEventNo());
        evidence.put("scene", event.getScene());
        evidence.put("bizType", event.getBizType());
        evidence.put("bizNo", event.getBizNo());
        evidence.put("userId", event.getUserId());
        evidence.put("merchantId", event.getMerchantId());
        return writeJson(evidence);
    }

    private Map<String, Object> payload(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("风控事件 payload 解析失败", e);
        }
    }

    private List<RiskRuleHitDTO> readHits(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<RiskRuleHitDTO>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("风控决策命中规则解析失败", e);
        }
    }

    private String safeMessage(String action) {
        return switch (action == null ? RiskDecisionResult.ACTION_PASS : action) {
            case RiskDecisionResult.ACTION_REJECT -> "当前操作存在安全风险，已被拒绝";
            case RiskDecisionResult.ACTION_FREEZE -> "当前对象存在安全风险，平台正在核实";
            case RiskDecisionResult.ACTION_MANUAL_REVIEW -> "当前操作进入安全审核，请稍后重试";
            case RiskDecisionResult.ACTION_LIMIT -> "当前操作受到安全限制";
            case RiskDecisionResult.ACTION_VERIFY -> "当前操作需要补充安全验证";
            case RiskDecisionResult.ACTION_WATCH -> "当前操作已记录安全观察";
            default -> "success";
        };
    }

    private Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null ? null : Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("风控数据序列化失败", e);
        }
    }

    private record Subject(String type, Long id) {
    }
}
