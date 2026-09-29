package com.example.risk.service;

import api.risk.RiskEvaluateRequest;
import api.risk.RiskRuleHitDTO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.risk.entity.RiskCase;
import com.example.risk.entity.RiskCaseNote;
import com.example.risk.entity.RiskEvent;
import com.example.risk.mapper.RiskCaseMapper;
import com.example.risk.mapper.RiskCaseNoteMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

@Service
public class RiskCaseService {
    private static final DateTimeFormatter HOUR_BUCKET = DateTimeFormatter.ofPattern("yyyyMMddHH");

    private final RiskCaseMapper caseMapper;
    private final RiskCaseNoteMapper noteMapper;

    public RiskCaseService(RiskCaseMapper caseMapper, RiskCaseNoteMapper noteMapper) {
        this.caseMapper = caseMapper;
        this.noteMapper = noteMapper;
    }

    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public RiskCase createAutoCase(RiskEvent event, String decisionNo, String action, String riskLevel,
                                   int riskScore, List<RiskRuleHitDTO> hitRules) {
        if (!shouldCreate(action, riskLevel, hitRules)) {
            return null;
        }
        Subject subject = subjectOf(event);
        String dedupKey = dedupKey(event, subject);
        RiskCase existing = caseMapper.selectOne(new LambdaQueryWrapper<RiskCase>()
                .eq(RiskCase::getDedupKey, dedupKey));
        if (existing != null) {
            LocalDateTime now = LocalDateTime.now();
            if ("RESOLVED".equals(existing.getStatus()) || "CLOSED".equals(existing.getStatus())) {
                if (caseMapper.reopen(existing.getId(), decisionNo, riskLevel, riskScore, now) > 0) {
                    insertNote(existing.getCaseNo(), "REOPEN", existing.getStatus(), "OPEN",
                            "同键高风险事件再次出现，案件重开", decisionNo);
                }
            } else {
                caseMapper.updateLatest(existing.getId(), decisionNo, riskLevel, riskScore, now);
                insertNote(existing.getCaseNo(), "PROCESS", existing.getStatus(), existing.getStatus(),
                        "同键事件再次命中，已更新最新决策", decisionNo);
            }
            return caseMapper.selectById(existing.getId());
        }

        LocalDateTime now = LocalDateTime.now();
        RiskCase riskCase = new RiskCase();
        riskCase.setCaseNo(nextCaseNo());
        riskCase.setDedupKey(dedupKey);
        riskCase.setDecisionNo(decisionNo);
        riskCase.setScene(event.getScene());
        riskCase.setBizType(event.getBizType());
        riskCase.setBizNo(event.getBizNo());
        riskCase.setSubjectType(subject.type());
        riskCase.setSubjectId(subject.id());
        riskCase.setRiskLevel(riskLevel);
        riskCase.setRiskScore(riskScore);
        riskCase.setStatus("OPEN");
        riskCase.setCommandStatus("NONE");
        riskCase.setCommandRetryCount(0);
        riskCase.setReopenCount(0);
        riskCase.setCreatedTime(now);
        riskCase.setUpdatedTime(now);
        caseMapper.insert(riskCase);
        insertNote(riskCase.getCaseNo(), "CREATE", null, "OPEN", "风控决策自动创建案件", decisionNo);
        return riskCase;
    }

    private boolean shouldCreate(String action, String riskLevel, List<RiskRuleHitDTO> hitRules) {
        if ("MANUAL_REVIEW".equals(action) || "FREEZE".equals(action)) {
            return true;
        }
        if ("REJECT".equals(action)) {
            return hitRules != null && hitRules.stream().anyMatch(rule -> Boolean.TRUE.equals(rule.getAutoCase()));
        }
        return ("HIGH".equals(riskLevel) || "CRITICAL".equals(riskLevel)) && !"PASS".equals(action);
    }

    private String dedupKey(RiskEvent event, Subject subject) {
        if (event.getBizNo() != null && !event.getBizNo().isBlank()) {
            return event.getScene() + ":" + event.getBizNo();
        }
        return event.getScene() + ":" + subject.type() + ":" + subject.id() + ":"
                + HOUR_BUCKET.format(event.getOccurredTime());
    }

    private Subject subjectOf(RiskEvent event) {
        if ("ITEM".equalsIgnoreCase(event.getBizType()) && event.getItemId() != null) {
            return new Subject("ITEM", event.getItemId());
        }
        if ("MERCHANT".equalsIgnoreCase(event.getBizType()) && event.getMerchantId() != null) {
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

    private void insertNote(String caseNo, String type, String before, String after,
                            String content, String decisionNo) {
        RiskCaseNote note = new RiskCaseNote();
        note.setCaseNo(caseNo);
        note.setNoteType(type);
        note.setOperatorId(0L);
        note.setOperatorName("RISK_SYSTEM");
        note.setContent(content);
        note.setBeforeStatus(before);
        note.setAfterStatus(after);
        note.setEvidenceJson("{\"decisionNo\":\"" + decisionNo + "\"}");
        note.setCreatedTime(LocalDateTime.now());
        noteMapper.insert(note);
    }

    private String nextCaseNo() {
        return "RC-" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().replace("-", "");
    }

    private record Subject(String type, Long id) {
    }
}
