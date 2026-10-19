package com.example.agent.customer.outbound;

import api.response.ApiResponse;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

import static com.example.agent.customer.CustomerJson.read;

@RestController
@RequestMapping("/api/agent/integrations")
public class CustomerIntegrationController {

    private final EventVerifier eventVerifier;
    private final CustomerOutboundService outboundService;

    public CustomerIntegrationController(EventVerifier eventVerifier, CustomerOutboundService outboundService) {
        this.eventVerifier = eventVerifier;
        this.outboundService = outboundService;
    }

    @PostMapping("/{source}/events")
    public Object ingest(@PathVariable String source,
                         @RequestBody String rawBody,
                         HttpServletRequest request) {
        String timestamp = request.getHeader("X-Event-Timestamp");
        String nonce = request.getHeader("X-Event-Nonce");
        String signature = request.getHeader("X-Event-Signature");

        // 验签与防重放
        eventVerifier.verify(source, request.getMethod(), request.getRequestURI(), timestamp, nonce, rawBody, signature);

        JsonNode root = read(rawBody);
        var result = outboundService.ingestEvent(source, root, rawBody);

        return ApiResponse.success(Map.of(
                "receiptNo", result.receiptNo(),
                "status", result.status(),
                "duplicate", result.duplicate()
        ));
    }
}
