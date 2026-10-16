package com.example.item.controller;

import api.context.UserContext;
import com.example.item.config.PaymentCallbackProperties;
import com.example.item.dto.PaymentCallbackRequest;
import com.example.item.dto.PaymentCallbackResponse;
import com.example.item.dto.PaymentOrderResponse;
import com.example.item.entity.PaymentOrder;
import com.example.item.service.PaymentCallbackRejectedException;
import com.example.item.service.PaymentCallbackService;
import com.example.item.service.TradeOrderService;
import com.example.item.service.PaymentCallbackVerifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@Slf4j
@RequestMapping("/api/payment")
@Profile({"local", "dev", "test", "default"})
public class MockPaymentController {
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final TradeOrderService tradeOrderService;
    private final PaymentCallbackService paymentCallbackService;
    private final PaymentCallbackVerifier paymentCallbackVerifier;
    private final PaymentCallbackProperties paymentCallbackProperties;
    private final ObjectMapper objectMapper;
    private final com.example.item.payment.application.PaymentApplicationService paymentApplicationService;
    private final com.example.item.payment.mock.MockChannelEngine mockChannelEngine;
    private final com.example.item.payment.application.PaymentConfirmationService paymentConfirmationService;

    public MockPaymentController(
            TradeOrderService tradeOrderService,
            PaymentCallbackService paymentCallbackService,
            PaymentCallbackVerifier paymentCallbackVerifier,
            PaymentCallbackProperties paymentCallbackProperties,
            ObjectMapper objectMapper,
            com.example.item.payment.application.PaymentApplicationService paymentApplicationService,
            com.example.item.payment.mock.MockChannelEngine mockChannelEngine,
            com.example.item.payment.application.PaymentConfirmationService paymentConfirmationService
    ) {
        this.tradeOrderService = tradeOrderService;
        this.paymentCallbackService = paymentCallbackService;
        this.paymentCallbackVerifier = paymentCallbackVerifier;
        this.paymentCallbackProperties = paymentCallbackProperties;
        this.objectMapper = objectMapper;
        this.paymentApplicationService = paymentApplicationService;
        this.mockChannelEngine = mockChannelEngine;
        this.paymentConfirmationService = paymentConfirmationService;
    }

    @GetMapping("/{paymentNo}")
    public ResponseEntity<PaymentCallbackResponse> detail(@PathVariable String paymentNo) {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            return response(HttpStatus.UNAUTHORIZED, "请先登录", null);
        }
        try {
            PaymentOrder payment = getVisiblePayment(paymentNo, userId);
            return response(HttpStatus.OK, "success", paymentResponse(payment));
        } catch (Exception e) {
            return response(HttpStatus.NOT_FOUND, e.getMessage(), null);
        }
    }

    @PostMapping("/{paymentNo}/start")
    public ResponseEntity<PaymentCallbackResponse> start(@PathVariable String paymentNo) {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            return response(HttpStatus.UNAUTHORIZED, "请先登录", null);
        }
        try {
            getVisiblePayment(paymentNo, userId);
            return response(HttpStatus.OK, "支付中", paymentResponse(tradeOrderService.startPay(paymentNo)));
        } catch (Exception e) {
            return response(HttpStatus.BAD_REQUEST, e.getMessage(), null);
        }
    }

    @PostMapping("/{paymentNo}/mock-pay")
    public ResponseEntity<PaymentCallbackResponse> mockPay(
            @PathVariable String paymentNo,
            @RequestBody Map<String, Object> body
    ) {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            return response(HttpStatus.UNAUTHORIZED, "请先登录", null);
        }
        if (!paymentCallbackProperties.mockEnabled()) {
            return response(HttpStatus.FORBIDDEN, "PAY_MOCK_DISABLED", null);
        }
        try {
            PaymentOrder payment = getVisiblePayment(paymentNo, userId);
            String result = "FAIL".equalsIgnoreCase(String.valueOf(body.get("result"))) ? "FAIL" : "SUCCESS";
            PaymentCallbackRequest request = buildMockCallback(payment, result, body);
            String callbackResult = paymentCallbackService.handle(request);
            return response(mockPayStatus(callbackResult), callbackResult,
                    tradeOrderService.getVisibleOrder(userId, payment.getOrderNo()));
        } catch (PaymentCallbackRejectedException exception) {
            return response(exception.status(), exception.code(), null);
        } catch (Exception e) {
            log.warn("mock payment failed, paymentNo={}", paymentNo, e);
            return response(HttpStatus.BAD_REQUEST, "支付处理失败", null);
        }
    }

    @PostMapping("/callback")
    public ResponseEntity<PaymentCallbackResponse> callback(@Valid @RequestBody PaymentCallbackRequest request) {
        String callbackResult = paymentCallbackService.handle(request);
        return response(callbackStatus(callbackResult), callbackResult, null);
    }

    @GetMapping("/{paymentNo}/methods")
    public ResponseEntity<PaymentCallbackResponse> methods(@PathVariable String paymentNo) {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            return response(HttpStatus.UNAUTHORIZED, "请先登录", null);
        }
        try {
            List<Map<String, Object>> methods = paymentApplicationService.getAvailableMethods(paymentNo, userId);
            return response(HttpStatus.OK, "success", methods);
        } catch (Exception e) {
            return response(HttpStatus.BAD_REQUEST, e.getMessage(), null);
        }
    }

    @PostMapping("/{paymentNo}/attempts")
    public ResponseEntity<PaymentCallbackResponse> createAttempt(
            @PathVariable String paymentNo,
            @RequestBody(required = false) Map<String, String> body
    ) {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            return response(HttpStatus.UNAUTHORIZED, "请先登录", null);
        }
        try {
            String methodStr = (body != null && body.containsKey("paymentMethod"))
                    ? body.get("paymentMethod") : "WECHAT_PAY";
            String requestKey = (body != null && body.containsKey("requestKey"))
                    ? body.get("requestKey") : UUID.randomUUID().toString();
            com.example.item.payment.domain.PaymentMethod method =
                    com.example.item.payment.domain.PaymentMethod.valueOf(methodStr);
            Map<String, Object> attemptDTO = paymentApplicationService.createAttempt(paymentNo, method, requestKey, userId);
            return response(HttpStatus.OK, "success", attemptDTO);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return response(HttpStatus.BAD_REQUEST, e.getMessage(), null);
        } catch (Exception e) {
            log.error("Failed to create payment attempt for {}", paymentNo, e);
            return response(HttpStatus.INTERNAL_SERVER_ERROR, "创建支付尝试失败: " + e.getMessage(), null);
        }
    }

    @GetMapping("/attempts/{attemptNo}")
    public ResponseEntity<PaymentCallbackResponse> attemptDetail(@PathVariable String attemptNo) {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            return response(HttpStatus.UNAUTHORIZED, "请先登录", null);
        }
        try {
            Map<String, Object> detail = paymentApplicationService.getAttemptDetail(attemptNo, userId);
            return response(HttpStatus.OK, "success", detail);
        } catch (Exception e) {
            return response(HttpStatus.NOT_FOUND, e.getMessage(), null);
        }
    }

    @PostMapping("/attempts/{attemptNo}/mock-confirm")
    public ResponseEntity<PaymentCallbackResponse> mockConfirm(
            @PathVariable String attemptNo,
            @RequestBody(required = false) Map<String, Object> body
    ) {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            return response(HttpStatus.UNAUTHORIZED, "请先登录", null);
        }
        try {
            String result = (body != null && body.containsKey("result"))
                    ? String.valueOf(body.get("result")) : "SUCCESS";
            Map<String, Object> res = paymentApplicationService.mockConfirm(attemptNo, result, userId);
            return response(HttpStatus.OK, "success", res);
        } catch (Exception e) {
            log.warn("mock confirm failed for attemptNo={}", attemptNo, e);
            return response(HttpStatus.BAD_REQUEST, e.getMessage(), null);
        }
    }

    @PostMapping("/attempts/{attemptNo}/close")
    public ResponseEntity<PaymentCallbackResponse> closeAttempt(
            @PathVariable String attemptNo,
            @RequestBody(required = false) Map<String, String> body
    ) {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            return response(HttpStatus.UNAUTHORIZED, "请先登录", null);
        }
        try {
            String requestKey = (body != null && body.containsKey("requestKey"))
                    ? body.get("requestKey") : UUID.randomUUID().toString();
            Map<String, Object> res = paymentApplicationService.closeAttempt(attemptNo, requestKey, userId);
            return response(HttpStatus.OK, "success", res);
        } catch (Exception e) {
            return response(HttpStatus.BAD_REQUEST, e.getMessage(), null);
        }
    }

    @PostMapping("/channel-callback")
    public ResponseEntity<String> channelCallback(
            @RequestHeader(value = "X-Signature", required = false) String signature,
            @RequestBody String rawPayload
    ) {
        try {
            Map<String, Object> payload = objectMapper.readValue(rawPayload, Map.class);
            String profileId = (String) payload.get("profileId");
            if (profileId == null) {
                return ResponseEntity.badRequest().body("FAIL_PROFILE_MISSING");
            }
            if (!mockChannelEngine.verifyNotificationSignature(profileId, rawPayload, signature)) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("FAIL_SIGNATURE_INVALID");
            }
            var event = mockChannelEngine.parseVerifiedEvent(rawPayload, signature);
            paymentConfirmationService.acceptVerifiedFact(event);
            return ResponseEntity.ok("SUCCESS");
        } catch (Exception e) {
            log.error("Failed to process channel callback", e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("FAIL: " + e.getMessage());
        }
    }

    private PaymentCallbackRequest buildMockCallback(
            PaymentOrder payment,
            String result,
            Map<String, Object> body
    ) throws JsonProcessingException {
        long timestamp = Instant.now().toEpochMilli();
        String nonce = randomNonce();
        String rawPayload = objectMapper.writeValueAsString(body);
        PaymentCallbackRequest unsignedRequest = new PaymentCallbackRequest(
                "CB" + UUID.randomUUID().toString().replace("-", ""),
                payment.getPaymentNo(),
                payment.getOrderNo(),
                payment.getAmount(),
                result,
                timestamp,
                nonce,
                "0".repeat(64),
                rawPayload
        );
        return new PaymentCallbackRequest(
                unsignedRequest.callbackNo(),
                unsignedRequest.paymentNo(),
                unsignedRequest.orderNo(),
                unsignedRequest.amount(),
                unsignedRequest.result(),
                unsignedRequest.timestamp(),
                unsignedRequest.nonce(),
                paymentCallbackVerifier.sign(unsignedRequest, payment),
                unsignedRequest.rawPayload()
        );
    }

    private String randomNonce() {
        byte[] bytes = new byte[24];
        SECURE_RANDOM.nextBytes(bytes);
        StringBuilder nonce = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            nonce.append(String.format("%02x", value));
        }
        return nonce.toString();
    }

    private HttpStatus mockPayStatus(String code) {
        return switch (code) {
            case "PAY_CALLBACK_ACCEPTED", "PAY_CALLBACK_DUPLICATED" -> HttpStatus.OK;
            default -> HttpStatus.CONFLICT;
        };
    }

    private HttpStatus callbackStatus(String code) {
        return switch (code) {
            case "PAY_CALLBACK_ACCEPTED" -> HttpStatus.ACCEPTED;
            case "PAY_CALLBACK_DUPLICATED" -> HttpStatus.OK;
            case "PAY_CALLBACK_AMOUNT_MISMATCH" -> HttpStatus.BAD_REQUEST;
            case "PAY_EXPIRED_LATE_SUCCESS" -> HttpStatus.CONFLICT;
            default -> HttpStatus.CONFLICT;
        };
    }

    private PaymentOrder getVisiblePayment(String paymentNo, Long userId) {
        PaymentOrder payment = tradeOrderService.getPaymentByNo(paymentNo);
        tradeOrderService.getVisibleOrder(userId, payment.getOrderNo());
        return payment;
    }

    private PaymentOrderResponse paymentResponse(PaymentOrder payment) {
        return new PaymentOrderResponse(
                payment.getPaymentNo(),
                payment.getOrderNo(),
                payment.getAmount(),
                payment.getStatus(),
                payment.getExpireTime()
        );
    }

    private ResponseEntity<PaymentCallbackResponse> response(
            HttpStatus status,
            String message,
            Object data
    ) {
        return ResponseEntity.status(status).body(new PaymentCallbackResponse(status.value(), message, data));
    }
}
