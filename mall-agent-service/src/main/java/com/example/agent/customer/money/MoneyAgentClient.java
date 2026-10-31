package com.example.agent.customer.money;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
public class MoneyAgentClient {

    private final RestTemplate restTemplate;
    private final String a2aEndpointUrl;
    private final ObjectMapper objectMapper;

    public MoneyAgentClient(@Value("${money.agent.a2a-url:http://localhost:8086/a2a/money}") String a2aEndpointUrl,
                            RestTemplateBuilder builder,
                            ObjectMapper objectMapper) {
        this.a2aEndpointUrl = a2aEndpointUrl;
        this.restTemplate = builder
                .setConnectTimeout(Duration.ofSeconds(3))
                .setReadTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = objectMapper;
    }

    public MoneyStartResponse start(String orderNo, String problemFamily, String targetRef, Long userId) {
        try {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("orderNo", orderNo);
            params.put("problemFamily", problemFamily);
            params.put("targetRef", targetRef != null ? targetRef : orderNo);
            params.put("userId", userId != null ? userId : 0L);

            Map<String, Object> payload = Map.of(
                    "jsonrpc", "2.0",
                    "id", "call-" + UUID.randomUUID().toString().substring(0, 8),
                    "method", "money.start",
                    "params", params
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(payload, headers);

            @SuppressWarnings("unchecked")
            Map<String, Object> resp = restTemplate.postForObject(a2aEndpointUrl, entity, Map.class);
            if (resp != null && resp.containsKey("result")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> result = (Map<String, Object>) resp.get("result");
                String caseNo = (String) result.get("caseNo");
                String taskNo = (String) result.get("taskNo");
                String caseState = (String) result.get("caseState");
                String taskState = (String) result.get("taskState");
                String outcome = (String) result.get("outcome");
                Boolean isNew = (Boolean) result.get("isNew");
                return new MoneyStartResponse(caseNo, taskNo, caseState, taskState, outcome, Boolean.TRUE.equals(isNew));
            }
        } catch (Exception e) {
            log.error("调用 MoneyAgent A2A money.start 异常, orderNo={}", orderNo, e);
        }
        return null;
    }

    public MoneyCaseResponse getCase(String caseNo) {
        try {
            Map<String, Object> payload = Map.of(
                    "jsonrpc", "2.0",
                    "id", "call-" + UUID.randomUUID().toString().substring(0, 8),
                    "method", "money.getCase",
                    "params", Map.of("caseNo", caseNo)
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(payload, headers);

            @SuppressWarnings("unchecked")
            Map<String, Object> resp = restTemplate.postForObject(a2aEndpointUrl, entity, Map.class);
            if (resp != null && resp.containsKey("result")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> result = (Map<String, Object>) resp.get("result");
                String cNo = (String) result.get("caseNo");
                String state = (String) result.get("caseState");
                String outcome = (String) result.get("outcome");
                String reportNo = (String) result.get("reportNo");
                String factsJson = (String) result.get("factsJson");
                return new MoneyCaseResponse(cNo, state, outcome, reportNo, factsJson);
            }
        } catch (Exception e) {
            log.error("调用 MoneyAgent A2A money.getCase 异常, caseNo={}", caseNo, e);
        }
        return null;
    }

    public record MoneyStartResponse(String caseNo, String taskNo, String caseState, String taskState, String outcome, boolean isNew) {}
    public record MoneyCaseResponse(String caseNo, String caseState, String outcome, String reportNo, String factsJson) {}
}

