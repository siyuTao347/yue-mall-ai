package com.example.moneyagent.api;

import com.example.moneyagent.application.MoneyCaseService;
import com.example.moneyagent.persistence.entity.MoneyCaseEntity;
import com.example.moneyagent.persistence.entity.MoneyReportEntity;
import com.example.moneyagent.persistence.entity.MoneyTaskEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Slf4j
@RestController
@RequestMapping({"/internal/a2a/money", "/a2a/money"})
public class MoneyA2aEndpoint {

    private static final Set<String> VALID_FAMILIES = Set.of(
            "PAYMENT_CONFIRMATION", "EXCESS_PAYMENT", "REFUND_STATUS", "REFUND_CONFLICT",
            "SUSPENSE_CLEARING", "SETTLEMENT_DELAY", "DISPUTE_INVESTIGATION"
    );

    private final MoneyCaseService caseService;

    public MoneyA2aEndpoint(MoneyCaseService caseService) {
        this.caseService = caseService;
    }

    @PostMapping
    public Map<String, Object> handleJsonRpc(@RequestBody Map<String, Object> body) {
        Object id = body.get("id");
        String method = (String) body.get("method");
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) body.getOrDefault("params", Map.of());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);

        try {
            if ("money.start".equalsIgnoreCase(method) || "money.investigate.v1".equalsIgnoreCase(method)) {
                String orderNo = (String) params.get("orderNo");
                String rawProblem = (String) params.getOrDefault("problemFamily", "PAYMENT_CONFIRMATION");
                String problemFamily = mapProblemFamily(rawProblem);
                String targetRef = (String) params.getOrDefault("targetRef", orderNo);
                Number userIdNum = (Number) params.get("userId");
                Long userId = userIdNum != null ? userIdNum.longValue() : 0L;
                String callerRole = (String) params.getOrDefault("role", "BUYER");

                MoneyCaseService.CaseExecutionResult execResult = caseService.createOrAttachCase(
                        "ORDER:" + orderNo, problemFamily, targetRef, "CUSTOMER", userId, callerRole);

                Map<String, Object> result = new LinkedHashMap<>();
                result.put("caseNo", execResult.caseEntity().getCaseNo());
                result.put("taskNo", execResult.taskEntity() != null ? execResult.taskEntity().getTaskNo() : null);
                result.put("caseState", execResult.caseEntity().getState());
                result.put("taskState", execResult.taskEntity() != null ? execResult.taskEntity().getState() : "COMPLETED");
                result.put("outcome", execResult.caseEntity().getOutcome());
                result.put("isNew", execResult.isNew());
                if (execResult.reportEntity() != null) {
                    result.put("reportNo", execResult.reportEntity().getReportNo());
                    result.put("factsJson", execResult.reportEntity().getInternalJson());
                }
                response.put("result", result);
            } else if ("money.getCase".equalsIgnoreCase(method) || "money.case-status.v1".equalsIgnoreCase(method)) {
                String caseNo = (String) params.get("caseNo");
                MoneyCaseService.CaseDetailResult detail = caseService.getCaseDetail(caseNo);
                if (detail == null) {
                    response.put("error", Map.of("code", -32602, "message", "案件不存在: " + caseNo));
                } else {
                    Map<String, Object> result = new LinkedHashMap<>();
                    MoneyCaseEntity c = detail.caseEntity();
                    MoneyTaskEntity t = detail.taskEntity();
                    MoneyReportEntity r = detail.reportEntity();

                    result.put("caseNo", c.getCaseNo());
                    result.put("scopeKey", c.getScopeKey());
                    result.put("orderNo", c.getOrderNo());
                    result.put("caseState", c.getState());
                    result.put("outcome", c.getOutcome());
                    result.put("taskNo", t != null ? t.getTaskNo() : null);
                    result.put("taskState", t != null ? t.getState() : null);
                    if (r != null) {
                        result.put("reportNo", r.getReportNo());
                        result.put("outcome", r.getOutcome());
                        result.put("factsJson", r.getInternalJson());
                    }
                    response.put("result", result);
                }
            } else if ("money.provide-input.v1".equalsIgnoreCase(method)) {
                // 受理补充材料
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("accepted", true);
                result.put("message", "补充调查材料已受理");
                response.put("result", result);
            } else if ("money.cancel-task.v1".equalsIgnoreCase(method)) {
                String caseNo = (String) params.get("caseNo");
                boolean closed = caseService.closeCase(caseNo, "用户/调用方取消调查", 0L);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("canceled", closed);
                response.put("result", result);
            } else {
                response.put("error", Map.of("code", -32601, "message", "Method not found: " + method));
            }
        } catch (Exception e) {
            log.error("JSON-RPC 处理异常, method={}", method, e);
            response.put("error", Map.of("code", -32000, "message", e.getMessage() != null ? e.getMessage() : "Internal Error"));
        }

        return response;
    }

    private String mapProblemFamily(String raw) {
        if (raw == null) return "PAYMENT_CONFIRMATION";
        String normalized = raw.trim().toUpperCase();
        if (VALID_FAMILIES.contains(normalized)) {
            return normalized;
        }
        // 精确别名映射，避免模糊匹配吞掉 EXCESS_PAYMENT
        if (normalized.equals("EXCESS") || normalized.equals("EXCESS_PAY")) {
            return "EXCESS_PAYMENT";
        }
        if (normalized.equals("REFUND") || normalized.equals("REFUND_ANOMALY")) {
            return "REFUND_STATUS";
        }
        if (normalized.equals("PAYMENT") || normalized.equals("PAYMENT_ANOMALY")) {
            return "PAYMENT_CONFIRMATION";
        }
        return "PAYMENT_CONFIRMATION";
    }
}
