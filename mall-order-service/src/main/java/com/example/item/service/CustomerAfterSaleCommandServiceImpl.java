package com.example.item.service;

import api.agent.CustomerAfterSaleCommandDubboService;
import com.example.item.entity.Arbitration;
import com.example.item.entity.Dispute;
import com.example.item.entity.DisputeMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboService;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@DubboService(timeout = 5000, retries = 0)
@Slf4j
public class CustomerAfterSaleCommandServiceImpl implements CustomerAfterSaleCommandDubboService {

    private final TradeOrderService tradeOrderService;
    private final ObjectMapper objectMapper;
    // 内存与短时幂等缓存（支持 operationId 防重与结果重放）
    private final ConcurrentHashMap<String, AfterSaleCommandResult> idempotencyMap = new ConcurrentHashMap<>();

    public CustomerAfterSaleCommandServiceImpl(TradeOrderService tradeOrderService, ObjectMapper objectMapper) {
        this.tradeOrderService = tradeOrderService;
        this.objectMapper = objectMapper;
    }

    @Override
    public AfterSaleCommandResult createAfterSaleRequest(String operationId, String authorizationContextJson, String requestJson) {
        if (operationId != null && idempotencyMap.containsKey(operationId)) {
            log.info("createAfterSaleRequest idempotent hit: operationId={}", operationId);
            return idempotencyMap.get(operationId);
        }
        try {
            JsonNode authNode = objectMapper.readTree(authorizationContextJson != null ? authorizationContextJson : "{}");
            Long subjectUserId = authNode.path("subjectUserId").asLong(0L);
            if (subjectUserId <= 0) {
                return AfterSaleCommandResult.fail("UNAUTHORIZED", "未授权的主体用户");
            }
            JsonNode reqNode = objectMapper.readTree(requestJson != null ? requestJson : "{}");
            String orderNo = reqNode.path("orderNo").asText(null);
            String disputeType = reqNode.path("requestType").asText(reqNode.path("disputeType").asText("REFUND"));
            String reason = reqNode.path("reason").asText(reqNode.path("description").asText(""));
            BigDecimal refundAmount = reqNode.has("requestedAmount") ? new BigDecimal(reqNode.path("requestedAmount").asText("0")) : null;

            Dispute dispute = tradeOrderService.openDispute(subjectUserId, orderNo, disputeType, reason, refundAmount);
            AfterSaleCommandResult result = AfterSaleCommandResult.ok(dispute.getDisputeNo(), dispute.getStatus(), objectMapper.writeValueAsString(dispute));
            if (operationId != null) {
                idempotencyMap.put(operationId, result);
            }
            return result;
        } catch (Exception e) {
            log.error("createAfterSaleRequest error: operationId={}, err={}", operationId, e.getMessage());
            return AfterSaleCommandResult.fail("AFTER_SALE_COMMAND_FAILED", e.getMessage());
        }
    }

    @Override
    public AfterSaleCommandResult submitAdditionalEvidence(String operationId, String authorizationContextJson, String disputeNo, String evidenceRefsJson) {
        if (operationId != null && idempotencyMap.containsKey(operationId)) {
            return idempotencyMap.get(operationId);
        }
        try {
            JsonNode authNode = objectMapper.readTree(authorizationContextJson != null ? authorizationContextJson : "{}");
            Long subjectUserId = authNode.path("subjectUserId").asLong(0L);
            List<DisputeMessage> msgs = tradeOrderService.addDisputeMessage(subjectUserId, disputeNo, "补充证据/资料：" + evidenceRefsJson);
            AfterSaleCommandResult result = AfterSaleCommandResult.ok(disputeNo, "EVIDENCE_SUBMITTED", objectMapper.writeValueAsString(msgs));
            if (operationId != null) {
                idempotencyMap.put(operationId, result);
            }
            return result;
        } catch (Exception e) {
            log.error("submitAdditionalEvidence error: operationId={}, err={}", operationId, e.getMessage());
            return AfterSaleCommandResult.fail("EVIDENCE_SUBMIT_FAILED", e.getMessage());
        }
    }

    @Override
    public AfterSaleCommandResult submitReviewDecision(String operationId, String authorizationContextJson, String disputeNo, String expectedBizVersion, String decision) {
        if (operationId != null && idempotencyMap.containsKey(operationId)) {
            return idempotencyMap.get(operationId);
        }
        try {
            JsonNode authNode = objectMapper.readTree(authorizationContextJson != null ? authorizationContextJson : "{}");
            Long adminId = authNode.path("operatorUserId").asLong(1L);
            String reason = authNode.path("reviewReason").asText("客服工作流审核推进");

            Arbitration arbitration = tradeOrderService.arbitrate(adminId, disputeNo, decision, reason);
            AfterSaleCommandResult result = AfterSaleCommandResult.ok(disputeNo, arbitration.getResult(), objectMapper.writeValueAsString(arbitration));
            if (operationId != null) {
                idempotencyMap.put(operationId, result);
            }
            return result;
        } catch (Exception e) {
            log.error("submitReviewDecision error: operationId={}, err={}", operationId, e.getMessage());
            return AfterSaleCommandResult.fail("REVIEW_DECISION_FAILED", e.getMessage());
        }
    }

    @Override
    public AfterSaleCommandResult queryCommandResult(String operationId, String authorizationContextJson) {
        if (operationId != null && idempotencyMap.containsKey(operationId)) {
            return idempotencyMap.get(operationId);
        }
        return AfterSaleCommandResult.fail("COMMAND_NOT_FOUND", "未找到指定命令执行结果");
    }
}
