package com.example.agent.customer.money.transport;

import api.agent.money.MoneyIdGenerator;
import api.agent.money.MoneyProblemFamily;
import api.agent.money.MoneySecuritySupport;
import api.agent.money.contract.*;
import com.example.agent.customer.money.MoneyAgentClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.*;

/**
 * 基于标准 A2A v1.0.0 协议的 MoneyAgentClient 传输实现
 * <p>
 * 支持：
 * 1. 标准 JSON-RPC 2.0 (SendMessage / GetTask / CancelTask)
 * 2. 双层安全认证头 (Authorization: Bearer 服务身份 + X-Money-Delegation 委托票据)
 * 3. 结构化错误解析 (code, retryable, traceId)
 * </p>
 */
@Slf4j
@Component
public class HttpA2aMoneyAgentClient implements MoneyAgentClient {

    private final RestTemplate restTemplate;
    private final String a2aEndpointUrl;
    private final ObjectMapper objectMapper;

    public HttpA2aMoneyAgentClient(
            @Value("${money.agent.a2a-url:http://localhost:8086/internal/a2a/money}") String a2aEndpointUrl,
            RestTemplateBuilder builder,
            ObjectMapper objectMapper) {
        this.a2aEndpointUrl = a2aEndpointUrl;
        this.restTemplate = builder
                .setConnectTimeout(Duration.ofSeconds(3))
                .setReadTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = objectMapper;
    }

    @Override
    public MoneyTaskReceipt start(DelegatedRequest<MoneyInvestigationRequest> request) {
        if (request == null || request.getPayload() == null) {
            throw new MoneyAgentException("INVALID_REQUEST", "调查请求负载不能为空", false, null);
        }

        MoneyInvestigationRequest payload = request.getPayload();
        Map<String, Object> dataPart = new LinkedHashMap<>();
        dataPart.put("kind", "data");
        dataPart.put("data", payload);

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "user");
        message.put("parts", List.of(dataPart));

        Map<String, Object> configuration = new LinkedHashMap<>();
        configuration.put("returnImmediately", true);
        configuration.put("historyLength", 0);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("message", message);
        params.put("configuration", configuration);

        String rpcId = "rpc-" + UUID.randomUUID().toString().substring(0, 8);
        JsonNode resultNode = executeRpc("SendMessage", params, rpcId, request.getCredential());

        // 解析 A2A Task 中的 metadata.mall.money.receipt.v1
        JsonNode metadata = resultNode.path("metadata");
        JsonNode receiptNode = metadata.path("mall.money.receipt.v1");
        if (receiptNode.isMissingNode() || receiptNode.isNull()) {
            // 兼容顶层直接映射
            receiptNode = resultNode;
        }

        return MoneyTaskReceipt.builder()
                .schemaVersion(receiptNode.path("schemaVersion").asInt(1))
                .requestId(receiptNode.path("requestId").asText(payload.getRequestId()))
                .caseNo(receiptNode.path("caseNo").asText(resultNode.path("caseNo").asText(null)))
                .taskNo(receiptNode.path("taskNo").asText(resultNode.path("id").asText(null)))
                .contextId(receiptNode.path("contextId").asText(resultNode.path("contextId").asText(null)))
                .acceptedAt(receiptNode.path("acceptedAt").asText(resultNode.path("createdAt").asText(null)))
                .reused(receiptNode.path("reused").asBoolean(false))
                .projectionRevision(receiptNode.path("projectionRevision").asLong(1L))
                .build();
    }

    @Override
    public MoneyTaskView get(MoneyTaskReference ref, MoneyCallerCredential credential) {
        if (ref == null || ref.getTaskNo() == null) {
            throw new MoneyAgentException("INVALID_ARGUMENT", "任务引用不能为空", false, null);
        }

        Map<String, Object> params = Map.of("taskId", ref.getTaskNo());
        String rpcId = "rpc-" + UUID.randomUUID().toString().substring(0, 8);
        JsonNode resultNode = executeRpc("GetTask", params, rpcId, credential);

        JsonNode metadata = resultNode.path("metadata");
        JsonNode viewNode = metadata.path("mall.money.view.v1");
        if (viewNode.isMissingNode() || viewNode.isNull()) {
            viewNode = resultNode;
        }

        return MoneyTaskView.builder()
                .taskNo(resultNode.path("id").asText(ref.getTaskNo()))
                .caseNo(viewNode.path("caseNo").asText(ref.getCaseNo()))
                .contextId(resultNode.path("contextId").asText(null))
                .taskState(resultNode.path("state").asText("WORKING"))
                .caseState(viewNode.path("caseState").asText("OPEN"))
                .taskVersion(viewNode.path("taskVersion").asLong(1L))
                .caseVersion(viewNode.path("caseVersion").asLong(1L))
                .projectionRevision(viewNode.path("projectionRevision").asLong(1L))
                .publicSummary(viewNode.path("publicSummary").asText(null))
                .resolutionOutcome(viewNode.path("resolutionOutcome").asText("UNDETERMINED"))
                .reportRef(viewNode.path("reportRef").asText(null))
                .updatedAt(viewNode.path("updatedAt").asText(null))
                .build();
    }

    @Override
    public MoneyCaseView getCase(MoneyCaseReference ref, MoneyCallerCredential credential) {
        if (ref == null || ref.getCaseNo() == null) {
            throw new MoneyAgentException("INVALID_ARGUMENT", "案件引用不能为空", false, null);
        }

        Map<String, Object> dataPart = Map.of(
                "kind", "data",
                "data", Map.of("operation", "GET_CASE", "caseNo", ref.getCaseNo())
        );
        Map<String, Object> message = Map.of(
                "role", "user",
                "parts", List.of(dataPart)
        );
        Map<String, Object> params = Map.of("message", message);
        String rpcId = "rpc-" + UUID.randomUUID().toString().substring(0, 8);

        JsonNode resultNode = executeRpc("SendMessage", params, rpcId, credential);

        // 如果返回即时 Message，提取 data Part
        JsonNode parts = resultNode.path("parts");
        JsonNode caseData = resultNode;
        if (parts.isArray() && parts.size() > 0) {
            for (JsonNode part : parts) {
                if ("data".equalsIgnoreCase(part.path("kind").asText()) && part.has("data")) {
                    caseData = part.path("data");
                    break;
                }
            }
        }

        return MoneyCaseView.builder()
                .caseNo(caseData.path("caseNo").asText(ref.getCaseNo()))
                .currentTaskNo(caseData.path("currentTaskNo").asText(caseData.path("taskNo").asText(null)))
                .contextId(caseData.path("contextId").asText(null))
                .caseState(caseData.path("caseState").asText("OPEN"))
                .taskState(caseData.path("taskState").asText(null))
                .caseVersion(caseData.path("caseVersion").asLong(1L))
                .taskVersion(caseData.path("taskVersion").asLong(1L))
                .projectionRevision(caseData.path("projectionRevision").asLong(1L))
                .publicSummary(caseData.path("publicSummary").asText(null))
                .reportRef(caseData.path("reportRef").asText(caseData.path("reportNo").asText(null)))
                .resolutionOutcome(caseData.path("resolutionOutcome").asText(caseData.path("outcome").asText("UNDETERMINED")))
                .updatedAt(caseData.path("updatedAt").asText(null))
                .build();
    }

    @Override
    public MoneyTaskView provideInput(DelegatedRequest<MoneyAdditionalInput> input) {
        if (input == null || input.getPayload() == null) {
            throw new MoneyAgentException("INVALID_ARGUMENT", "补充输入负载不能为空", false, null);
        }

        MoneyAdditionalInput payload = input.getPayload();
        Map<String, Object> dataPart = Map.of(
                "kind", "data",
                "data", payload
        );
        Map<String, Object> message = Map.of(
                "role", "user",
                "parts", List.of(dataPart)
        );
        Map<String, Object> params = Map.of(
                "message", message,
                "taskId", payload.getTaskNo() != null ? payload.getTaskNo() : ""
        );

        String rpcId = "rpc-" + UUID.randomUUID().toString().substring(0, 8);
        JsonNode resultNode = executeRpc("SendMessage", params, rpcId, input.getCredential());
        return MoneyTaskView.builder()
                .taskNo(resultNode.path("id").asText(payload.getTaskNo()))
                .caseNo(payload.getCaseNo())
                .taskState(resultNode.path("state").asText("WORKING"))
                .publicSummary("补充材料已受理")
                .build();
    }

    @Override
    public MoneyCancelReceipt cancel(DelegatedRequest<MoneyCancelRequest> request) {
        if (request == null || request.getPayload() == null) {
            throw new MoneyAgentException("INVALID_ARGUMENT", "取消请求不能为空", false, null);
        }

        MoneyCancelRequest payload = request.getPayload();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("taskId", payload.getTaskNo());
        params.put("metadata", Map.of("mall.money.control.v1", payload));

        String rpcId = "rpc-" + UUID.randomUUID().toString().substring(0, 8);
        JsonNode resultNode = executeRpc("CancelTask", params, rpcId, request.getCredential());

        return MoneyCancelReceipt.builder()
                .requestId(payload.getRequestId())
                .caseNo(payload.getCaseNo())
                .taskNo(payload.getTaskNo())
                .canceled("CANCELED".equalsIgnoreCase(resultNode.path("state").asText()))
                .taskState(resultNode.path("state").asText("CANCELED"))
                .message("任务已取消")
                .build();
    }

    // =========================================================================
    // 兼容原有方法的便捷实现
    // =========================================================================

    @Override
    public MoneyStartResponse start(String orderNo, String problemFamily, String targetRef, Long userId) {
        try {
            Long uid = userId != null ? userId : 0L;
            String safeOrder = orderNo != null ? orderNo.trim() : "UNKNOWN";
            String scopeKey = "ORDER:" + safeOrder;
            String grantRef = MoneyIdGenerator.generateGrantNo();
            String requestId = "REQ_" + UUID.randomUUID().toString().replace("-", "").toUpperCase();

            // 生成双层认证票据
            String svcToken = MoneySecuritySupport.createServiceIdentityProof("mall-agent-service", "mall-money-services");
            String delToken = MoneySecuritySupport.createDelegationProof(
                    uid, "BUYER", scopeKey, "mall-money-services",
                    List.of("INVESTIGATE", "GET_CASE", "PROVIDE_INPUT", "CANCEL"), grantRef);

            MoneyCallerCredential credential = MoneyCallerCredential.builder()
                    .serviceIdentityToken(svcToken)
                    .delegationToken(delToken)
                    .callerService("mall-agent-service")
                    .actorUserId(uid)
                    .actorRole("BUYER")
                    .grantRef(grantRef)
                    .scopeKey(scopeKey)
                    .build();

            MoneyProblemFamily family = MoneyProblemFamily.fromString(problemFamily);

            MoneyInvestigationRequest invReq = MoneyInvestigationRequest.builder()
                    .schema(MoneyInvestigationRequest.SCHEMA_V1)
                    .requestId(requestId)
                    .origin("CUSTOMER")
                    .subject(MoneyInvestigationRequest.Subject.builder()
                            .scopeKey(scopeKey)
                            .orderNo(safeOrder)
                            .attemptNo(targetRef != null && targetRef.startsWith("PA_") ? targetRef : null)
                            .refundNo(targetRef != null && targetRef.startsWith("RF_") ? targetRef : null)
                            .build())
                    .problemFamily(family)
                    .claim(MoneyInvestigationRequest.Claim.builder()
                            .text("客户发起资金异常排查")
                            .reportedAt(java.time.Instant.now().toString())
                            .build())
                    .delegationRef(grantRef)
                    .callerContext(MoneyInvestigationRequest.CallerContext.builder()
                            .dispatchNo(MoneyIdGenerator.generateDispatchNo())
                            .build())
                    .expectedScopeFingerprint(MoneySecuritySupport.computeFingerprint(scopeKey))
                    .build();

            MoneyTaskReceipt receipt = start(DelegatedRequest.of(invReq, credential));
            return new MoneyStartResponse(
                    receipt.getCaseNo(),
                    receipt.getTaskNo(),
                    "OPEN",
                    "SUBMITTED",
                    "UNDETERMINED",
                    !receipt.isReused()
            );
        } catch (MoneyAgentException e) {
            log.error("MoneyAgentClient.start 契约异常, code={}, retryable={}, traceId={}",
                    e.getCode(), e.isRetryable(), e.getTraceId(), e);
            throw e;
        } catch (Exception e) {
            log.error("MoneyAgentClient.start 系统异常", e);
            throw new MoneyAgentException("START_FAILED", e.getMessage(), true, null, -32000, e);
        }
    }

    @Override
    public MoneyCaseResponse getCase(String caseNo) {
        try {
            String svcToken = MoneySecuritySupport.createServiceIdentityProof("mall-agent-service", "mall-money-services");
            String delToken = MoneySecuritySupport.createDelegationProof(
                    0L, "BUYER", "", "mall-money-services",
                    List.of("GET_CASE"), "GRANT_DEFAULT");

            MoneyCallerCredential credential = MoneyCallerCredential.builder()
                    .serviceIdentityToken(svcToken)
                    .delegationToken(delToken)
                    .callerService("mall-agent-service")
                    .build();

            MoneyCaseView caseView = getCase(MoneyCaseReference.of(caseNo), credential);
            return new MoneyCaseResponse(
                    caseView.getCaseNo(),
                    caseView.getCaseState(),
                    caseView.getResolutionOutcome(),
                    caseView.getReportRef(),
                    null
            );
        } catch (MoneyAgentException e) {
            log.error("MoneyAgentClient.getCase 契约异常, code={}, traceId={}", e.getCode(), e.getTraceId(), e);
            throw e;
        } catch (Exception e) {
            log.error("MoneyAgentClient.getCase 系统异常, caseNo={}", caseNo, e);
            throw new MoneyAgentException("GET_CASE_FAILED", e.getMessage(), true, null, -32000, e);
        }
    }

    // =========================================================================
    // 底层 JSON-RPC 执行与双层认证头注入
    // =========================================================================

    private JsonNode executeRpc(String method, Map<String, Object> params, String rpcId, MoneyCallerCredential credential) {
        Map<String, Object> rpcBody = new LinkedHashMap<>();
        rpcBody.put("jsonrpc", "2.0");
        rpcBody.put("id", rpcId);
        rpcBody.put("method", method);
        rpcBody.put("params", params != null ? params : Collections.emptyMap());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        if (credential != null) {
            if (credential.getServiceIdentityToken() != null && !credential.getServiceIdentityToken().isBlank()) {
                headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + credential.getServiceIdentityToken().trim());
            }
            if (credential.getDelegationToken() != null && !credential.getDelegationToken().isBlank()) {
                headers.set("X-Money-Delegation", credential.getDelegationToken().trim());
            }
        }

        HttpEntity<Map<String, Object>> httpEntity = new HttpEntity<>(rpcBody, headers);

        try {
            String responseStr = restTemplate.postForObject(a2aEndpointUrl, httpEntity, String.class);
            if (responseStr == null || responseStr.isBlank()) {
                throw new MoneyAgentException("EMPTY_RESPONSE", "Money A2A 返回空响应", true, rpcId);
            }

            JsonNode root = objectMapper.readTree(responseStr);
            if (root.has("error") && !root.path("error").isNull()) {
                JsonNode err = root.path("error");
                int errCode = err.path("code").asInt(-32000);
                String errMsg = err.path("message").asText("Unknown RPC Error");
                JsonNode errData = err.path("data");
                String bizCode = errData.path("code").asText(String.valueOf(errCode));
                boolean retryable = errData.path("retryable").asBoolean(errCode == -32000 || errCode == -32603);
                String traceId = errData.path("traceId").asText(rpcId);
                throw new MoneyAgentException(bizCode, errMsg, retryable, traceId, errCode);
            }

            if (!root.has("result")) {
                throw new MoneyAgentException("INVALID_PROTOCOL_RESPONSE", "响应缺少 result 字段", false, rpcId);
            }

            return root.path("result");
        } catch (MoneyAgentException e) {
            throw e;
        } catch (HttpStatusCodeException e) {
            log.warn("Money A2A HTTP 异常, status={}", e.getStatusCode(), e);
            throw new MoneyAgentException("HTTP_" + e.getStatusCode().value(), e.getResponseBodyAsString(),
                    e.getStatusCode().is5xxServerError(), rpcId, e.getStatusCode().value(), e);
        } catch (ResourceAccessException e) {
            log.warn("Money A2A 网络访问异常", e);
            throw new MoneyAgentException("NETWORK_ERROR", e.getMessage(), true, rpcId, -32000, e);
        } catch (Exception e) {
            log.error("Money A2A JSON-RPC 调用异常, method={}", method, e);
            throw new MoneyAgentException("INTERNAL_ERROR", e.getMessage(), false, rpcId, -32000, e);
        }
    }
}

