package com.example.item.service;

import api.risk.RiskDecisionResult;
import api.risk.RiskCommandResultDTO;
import api.risk.RiskDubboService;
import api.risk.RiskEvaluateRequest;
import api.risk.RiskSupport;
import api.risk.SensitiveWordDTO;
import api.risk.SensitiveWordScanner;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

@Service
@Slf4j
public class RiskClient {
    private static final long SENSITIVE_WORD_CACHE_MILLIS = 60_000L;

    @DubboReference(timeout = 800, retries = 0, check = false)
    private RiskDubboService riskService;

    @Value("${risk.identity-salt:change-me-in-production}")
    private String identitySalt;

    private volatile List<SensitiveWordDTO> sensitiveWords;
    private volatile long sensitiveWordsLoadedAt;

    public RiskDecisionResult evaluate(RiskEvaluateRequest request) {
        try {
            RiskDecisionResult result = riskService.evaluate(request);
            return result == null ? degraded(request) : result;
        } catch (Exception e) {
            log.warn("同步风控评估降级, eventNo={}", eventNo(request), e);
            return degraded(request);
        }
    }

    public RiskDecisionResult recordEvent(RiskEvaluateRequest request) {
        try {
            return riskService.recordEvent(request);
        } catch (Exception e) {
            log.warn("观察风控事件记录失败, eventNo={}", eventNo(request), e);
            return degraded(request);
        }
    }

    public RiskDecisionResult recordEventForOrchestration(RiskEvaluateRequest request) {
        return riskService.recordEvent(request);
    }

    public void confirmAfterCommit(String eventNo) {
        if (eventNo == null || eventNo.isBlank()) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    confirm(eventNo);
                }
            });
            return;
        }
        confirm(eventNo);
    }

    public void confirmForOrchestration(String eventNo) {
        if (eventNo == null || eventNo.isBlank()) {
            return;
        }
        riskService.confirmEvent(eventNo);
    }

    public void confirmCommand(RiskCommandResultDTO result) {
        riskService.confirmCommand(result);
    }

    public List<SensitiveWordDTO> sensitiveWords() {
        long now = System.currentTimeMillis();
        List<SensitiveWordDTO> cached = sensitiveWords;
        if (cached != null && now - sensitiveWordsLoadedAt < SENSITIVE_WORD_CACHE_MILLIS) {
            return cached;
        }
        try {
            List<SensitiveWordDTO> words = riskService.getSensitiveWords();
            if (words != null && !words.isEmpty()) {
                sensitiveWords = words;
                sensitiveWordsLoadedAt = now;
                return words;
            }
        } catch (Exception e) {
            log.warn("敏感词库拉取失败，使用默认词库", e);
        }
        return SensitiveWordScanner.defaultWords();
    }

    public String ipHash() {
        return RiskSupport.hmacSha256(clientIp(), identitySalt);
    }

    public String deviceHash() {
        return RiskSupport.hmacSha256(deviceId(), identitySalt);
    }

    private void confirm(String eventNo) {
        try {
            riskService.confirmEvent(eventNo);
        } catch (Exception e) {
            log.warn("风控事件事务后确认失败, eventNo={}", eventNo, e);
        }
    }

    private RiskDecisionResult degraded(RiskEvaluateRequest request) {
        return RiskDecisionResult.degraded(eventNo(request));
    }

    private String eventNo(RiskEvaluateRequest request) {
        return request == null || request.getEventNo() == null ? "RISK-DEGRADED" : request.getEventNo();
    }

    private String clientIp() {
        HttpServletRequest request = currentRequest();
        if (request == null) {
            return null;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        return realIp != null && !realIp.isBlank() ? realIp.trim() : request.getRemoteAddr();
    }

    private String deviceId() {
        HttpServletRequest request = currentRequest();
        if (request == null) {
            return null;
        }
        String deviceId = request.getHeader("X-Device-Id");
        return deviceId != null && !deviceId.isBlank() ? deviceId.trim() : request.getHeader("User-Agent");
    }

    private HttpServletRequest currentRequest() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                ? attributes.getRequest() : null;
    }
}
