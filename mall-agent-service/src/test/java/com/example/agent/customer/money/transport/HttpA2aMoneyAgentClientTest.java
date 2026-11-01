package com.example.agent.customer.money.transport;

import api.agent.money.MoneyProblemFamily;
import api.agent.money.contract.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class HttpA2aMoneyAgentClientTest {

    private RestTemplate restTemplate;
    private MockRestServiceServer mockServer;
    private ObjectMapper objectMapper;
    private HttpA2aMoneyAgentClient client;

    @BeforeEach
    void setUp() throws Exception {
        objectMapper = new ObjectMapper();
        RestTemplateBuilder builder = new RestTemplateBuilder();
        client = new HttpA2aMoneyAgentClient("http://localhost:8086/internal/a2a/money", builder, objectMapper);

        // 获取内部 restTemplate 以绑定 MockRestServiceServer
        Field rtField = HttpA2aMoneyAgentClient.class.getDeclaredField("restTemplate");
        rtField.setAccessible(true);
        restTemplate = (RestTemplate) rtField.get(client);
        mockServer = MockRestServiceServer.createServer(restTemplate);
    }

    @Test
    @DisplayName("测试 start 方法发送标准 SendMessage 并携带双层认证头，成功解析受理回执")
    void testStartSuccess() {
        String responseJson = """
                {
                  "jsonrpc": "2.0",
                  "id": "rpc-1",
                  "result": {
                    "id": "MT0123456789ABCDEF0123456789ABCDEF",
                    "contextId": "CTX_0123456789ABCDEF0123456789ABCDEF",
                    "state": "SUBMITTED",
                    "createdAt": "2026-11-01T00:00:00Z",
                    "metadata": {
                      "mall.money.receipt.v1": {
                        "schemaVersion": 1,
                        "requestId": "REQ-TEST-001",
                        "caseNo": "MC0123456789ABCDEF0123456789ABCDEF",
                        "taskNo": "MT0123456789ABCDEF0123456789ABCDEF",
                        "contextId": "CTX_0123456789ABCDEF0123456789ABCDEF",
                        "acceptedAt": "2026-11-01T00:00:00Z",
                        "reused": false,
                        "projectionRevision": 1
                      }
                    }
                  }
                }
                """;

        mockServer.expect(requestTo("http://localhost:8086/internal/a2a/money"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer svc-token-test"))
                .andExpect(header("X-Money-Delegation", "del-token-test"))
                .andExpect(jsonPath("$.method").value("SendMessage"))
                .andExpect(jsonPath("$.params.configuration.returnImmediately").value(true))
                .andExpect(jsonPath("$.params.message.parts[0].data.requestId").value("REQ-TEST-001"))
                .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));

        MoneyCallerCredential credential = MoneyCallerCredential.builder()
                .serviceIdentityToken("svc-token-test")
                .delegationToken("del-token-test")
                .callerService("mall-agent-service")
                .build();

        MoneyInvestigationRequest invReq = MoneyInvestigationRequest.builder()
                .requestId("REQ-TEST-001")
                .problemFamily(MoneyProblemFamily.PAYMENT_CONFIRMATION)
                .build();

        MoneyTaskReceipt receipt = client.start(DelegatedRequest.of(invReq, credential));

        assertNotNull(receipt);
        assertEquals("MC0123456789ABCDEF0123456789ABCDEF", receipt.getCaseNo());
        assertEquals("MT0123456789ABCDEF0123456789ABCDEF", receipt.getTaskNo());
        assertEquals("REQ-TEST-001", receipt.getRequestId());
        assertEquals(1L, receipt.getProjectionRevision());
        mockServer.verify();
    }

    @Test
    @DisplayName("测试 get 方法发送标准 GetTask 并解析 TaskView")
    void testGetTaskSuccess() {
        String responseJson = """
                {
                  "jsonrpc": "2.0",
                  "id": "rpc-2",
                  "result": {
                    "id": "MT0123456789ABCDEF0123456789ABCDEF",
                    "state": "COMPLETED",
                    "metadata": {
                      "mall.money.view.v1": {
                        "caseNo": "MC0123456789ABCDEF0123456789ABCDEF",
                        "caseState": "OPEN",
                        "resolutionOutcome": "VERIFIED_NORMAL",
                        "publicSummary": "资金核对无异常"
                      }
                    }
                  }
                }
                """;

        mockServer.expect(requestTo("http://localhost:8086/internal/a2a/money"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.method").value("GetTask"))
                .andExpect(jsonPath("$.params.taskId").value("MT0123456789ABCDEF0123456789ABCDEF"))
                .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));

        MoneyCallerCredential credential = MoneyCallerCredential.builder().build();
        MoneyTaskView view = client.get(MoneyTaskReference.of("MT0123456789ABCDEF0123456789ABCDEF"), credential);

        assertNotNull(view);
        assertEquals("MT0123456789ABCDEF0123456789ABCDEF", view.getTaskNo());
        assertEquals("COMPLETED", view.getTaskState());
        assertEquals("VERIFIED_NORMAL", view.getResolutionOutcome());
        mockServer.verify();
    }

    @Test
    @DisplayName("测试 getCase 发送 SendMessage 查询案件并返回即时 Message")
    void testGetCaseSuccess() {
        String responseJson = """
                {
                  "jsonrpc": "2.0",
                  "id": "rpc-3",
                  "result": {
                    "role": "agent",
                    "parts": [
                      {
                        "kind": "data",
                        "data": {
                          "caseNo": "MC0123456789ABCDEF0123456789ABCDEF",
                          "currentTaskNo": "MT0123456789ABCDEF0123456789ABCDEF",
                          "caseState": "OPEN",
                          "resolutionOutcome": "NEEDS_REVIEW",
                          "publicSummary": "等待财务复核"
                        }
                      }
                    ]
                  }
                }
                """;

        mockServer.expect(requestTo("http://localhost:8086/internal/a2a/money"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.method").value("SendMessage"))
                .andExpect(jsonPath("$.params.message.parts[0].data.operation").value("GET_CASE"))
                .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));

        MoneyCallerCredential credential = MoneyCallerCredential.builder().build();
        MoneyCaseView caseView = client.getCase(MoneyCaseReference.of("MC0123456789ABCDEF0123456789ABCDEF"), credential);

        assertNotNull(caseView);
        assertEquals("MC0123456789ABCDEF0123456789ABCDEF", caseView.getCaseNo());
        assertEquals("NEEDS_REVIEW", caseView.getResolutionOutcome());
        mockServer.verify();
    }

    @Test
    @DisplayName("测试服务端返回业务错误时保留 code, retryable, traceId 并抛出 MoneyAgentException")
    void testRpcErrorHandling() {
        String responseJson = """
                {
                  "jsonrpc": "2.0",
                  "id": "rpc-4",
                  "error": {
                    "code": -32602,
                    "message": "未知问题分类: UNKNOWN_FAMILY",
                    "data": {
                      "code": "INVALID_PROBLEM_FAMILY",
                      "retryable": false,
                      "traceId": "trace-404"
                    }
                  }
                }
                """;

        mockServer.expect(requestTo("http://localhost:8086/internal/a2a/money"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));

        MoneyCallerCredential credential = MoneyCallerCredential.builder().build();
        MoneyInvestigationRequest invReq = MoneyInvestigationRequest.builder().requestId("R1").build();

        MoneyAgentException ex = assertThrows(MoneyAgentException.class, () ->
                client.start(DelegatedRequest.of(invReq, credential)));

        assertEquals("INVALID_PROBLEM_FAMILY", ex.getCode());
        assertFalse(ex.isRetryable());
        assertEquals("trace-404", ex.getTraceId());
        assertEquals(-32602, ex.getRpcCode());
        mockServer.verify();
    }
}

