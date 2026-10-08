package com.example.risk.controller;

import api.common.PageQuery;
import api.common.PageResult;
import api.common.TimeRangeQuery;
import api.util.JwtUtil;
import com.example.risk.dto.RiskCaseAssignRequest;
import com.example.risk.dto.RiskCaseCloseRequest;
import com.example.risk.dto.RiskCaseDetailDTO;
import com.example.risk.dto.RiskCaseListQuery;
import com.example.risk.dto.RiskCaseReopenRequest;
import com.example.risk.dto.RiskCaseResolveRequest;
import com.example.risk.dto.RiskCaseSummaryDTO;
import com.example.risk.dto.RiskCommandManualCompleteRequest;
import com.example.risk.dto.RiskCommandResendRequest;
import com.example.risk.entity.RiskCase;
import com.example.risk.exception.RiskApiException;
import com.example.risk.service.RiskCaseQueryService;
import com.example.risk.service.RiskCommandService;
import com.example.risk.service.RiskDictionaryService;
import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/risk")
public class RiskCaseAdminController {
    private final RiskCaseQueryService queryService;
    private final RiskCommandService riskCommandService;
    private final RiskDictionaryService dictionaryService;
    private final MeterRegistry meterRegistry;
    @Value("${pagination.default-page-size:20}")
    private int defaultPageSize;
    @Value("${pagination.max-page-size:100}")
    private int maxPageSize;

    public RiskCaseAdminController(RiskCaseQueryService queryService, RiskCommandService riskCommandService,
                                   RiskDictionaryService dictionaryService, MeterRegistry meterRegistry) {
        this.queryService = queryService;
        this.riskCommandService = riskCommandService;
        this.dictionaryService = dictionaryService;
        this.meterRegistry = meterRegistry;
    }

    @GetMapping("/dictionaries")
    public ResponseEntity<Map<String, Object>> dictionaries(
            @RequestHeader(value = "Authorization", required = false) String token,
            @RequestHeader(value = "X-User-Id", required = false) String userIdHeader,
            @RequestHeader(value = "X-User-Role", required = false) String roleHeader,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        Operator operator = requireAdmin(token, userIdHeader, roleHeader);
        if (operator == null) {
            return error(HttpStatus.FORBIDDEN, "无管理员权限", "RISK_FORBIDDEN", requestId);
        }
        return ok("success", dictionaryService.dictionary(), requestId);
    }

    @GetMapping("/cases")
    public ResponseEntity<Map<String, Object>> listCases(
            @RequestHeader(value = "Authorization", required = false) String token,
            @RequestHeader(value = "X-User-Id", required = false) String userIdHeader,
            @RequestHeader(value = "X-User-Role", required = false) String roleHeader,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String scene,
            @RequestParam(required = false) String riskLevel,
            @RequestParam(required = false) String commandStatus,
            @RequestParam(required = false) String subjectType,
            @RequestParam(required = false) Long subjectId,
            @RequestParam(required = false) String bizNo,
            @RequestParam(required = false) Long assignedTo,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime fromTime,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime toTime) {
        Operator operator = requireAdmin(token, userIdHeader, roleHeader);
        if (operator == null) {
            return error(HttpStatus.FORBIDDEN, "无管理员权限", "RISK_FORBIDDEN", requestId);
        }
        try {
            PageQuery pagination = PageQuery.of(page, pageSize, defaultPageSize, maxPageSize);
            TimeRangeQuery timeRange = new TimeRangeQuery(fromTime, toTime);
            timeRange.validate(92);
            requireOption("status", status, STATUSES);
            requireOption("scene", scene, SCENES);
            requireOption("riskLevel", riskLevel, RISK_LEVELS);
            requireOption("commandStatus", commandStatus, COMMAND_STATUSES);
            requireOption("subjectType", subjectType, SUBJECT_TYPES);
            requirePositive("subjectId", subjectId);
            requirePositive("assignedTo", assignedTo);
            if (bizNo != null && bizNo.length() > 64) {
                throw badRequest("bizNo 最长 64 个字符");
            }
            if (keyword != null && keyword.length() > 64) {
                throw badRequest("keyword 最长 64 个字符");
            }
            RiskCaseListQuery query = new RiskCaseListQuery(status, scene, riskLevel, commandStatus,
                    subjectType, subjectId, bizNo, assignedTo, keyword, timeRange);
            PageResult<RiskCaseSummaryDTO> result = Timer.builder("risk_case_list_duration_seconds")
                    .register(meterRegistry)
                    .record(() -> queryService.listCases(query, pagination.page(), pagination.pageSize()));
            return ok("success", result, requestId);
        } catch (RiskApiException e) {
            return error(HttpStatus.valueOf(e.httpStatus()), e.getMessage(), e.errorCode(), requestId);
        } catch (IllegalArgumentException e) {
            return error(HttpStatus.BAD_REQUEST, e.getMessage(), "RISK_VALIDATION_FAILED", requestId);
        }
    }

    @GetMapping("/cases/{caseNo}")
    public ResponseEntity<Map<String, Object>> detail(
            @RequestHeader(value = "Authorization", required = false) String token,
            @RequestHeader(value = "X-User-Id", required = false) String userIdHeader,
            @RequestHeader(value = "X-User-Role", required = false) String roleHeader,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId,
            @PathVariable String caseNo) {
        Operator operator = requireAdmin(token, userIdHeader, roleHeader);
        if (operator == null) {
            return error(HttpStatus.FORBIDDEN, "无管理员权限", "RISK_FORBIDDEN", requestId);
        }
        try {
            RiskCaseDetailDTO detail = queryService.detail(caseNo);
            return ok("success", detail, requestId);
        } catch (RiskApiException e) {
            return error(HttpStatus.valueOf(e.httpStatus()), e.getMessage(), e.errorCode(), requestId);
        }
    }

    @PostMapping("/cases/{caseNo}/assign")
    public ResponseEntity<Map<String, Object>> assign(
            @RequestHeader(value = "Authorization", required = false) String token,
            @RequestHeader(value = "X-User-Id", required = false) String userIdHeader,
            @RequestHeader(value = "X-User-Role", required = false) String roleHeader,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId,
            @PathVariable String caseNo,
            @Valid @RequestBody RiskCaseAssignRequest body) {
        Operator operator = requireAdmin(token, userIdHeader, roleHeader);
        if (operator == null) {
            return error(HttpStatus.FORBIDDEN, "无管理员权限", "RISK_FORBIDDEN", requestId);
        }
        RiskCase riskCase = riskCommandService.assign(caseNo, operator.id(), operator.name(),
                body.expectedStatus(), body.operationVersion());
        return ok("案件认领成功", riskCase, requestId);
    }

    @PostMapping("/cases/{caseNo}/resolve")
    public ResponseEntity<Map<String, Object>> resolve(
            @RequestHeader(value = "Authorization", required = false) String token,
            @RequestHeader(value = "X-User-Id", required = false) String userIdHeader,
            @RequestHeader(value = "X-User-Role", required = false) String roleHeader,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId,
            @PathVariable String caseNo,
            @Valid @RequestBody RiskCaseResolveRequest body) {
        Operator operator = requireAdmin(token, userIdHeader, roleHeader);
        if (operator == null) {
            return error(HttpStatus.FORBIDDEN, "无管理员权限", "RISK_FORBIDDEN", requestId);
        }
        RiskCase riskCase = riskCommandService.resolve(caseNo, operator.id(), operator.name(),
                body.expectedStatus(), body.operationVersion(), body.command(), body.reason(),
                body.actionParams());
        return ok("案件处理完成", riskCase, requestId);
    }

    @PostMapping("/cases/{caseNo}/close")
    public ResponseEntity<Map<String, Object>> close(
            @RequestHeader(value = "Authorization", required = false) String token,
            @RequestHeader(value = "X-User-Id", required = false) String userIdHeader,
            @RequestHeader(value = "X-User-Role", required = false) String roleHeader,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId,
            @PathVariable String caseNo,
            @Valid @RequestBody RiskCaseCloseRequest body) {
        Operator operator = requireAdmin(token, userIdHeader, roleHeader);
        if (operator == null) {
            return error(HttpStatus.FORBIDDEN, "无管理员权限", "RISK_FORBIDDEN", requestId);
        }
        RiskCase riskCase = riskCommandService.close(caseNo, operator.id(), operator.name(),
                body.expectedStatus(), body.expectedCommandStatus(), body.operationVersion(), body.reason());
        return ok("案件已关闭", riskCase, requestId);
    }

    @PostMapping("/cases/{caseNo}/reopen")
    public ResponseEntity<Map<String, Object>> reopen(
            @RequestHeader(value = "Authorization", required = false) String token,
            @RequestHeader(value = "X-User-Id", required = false) String userIdHeader,
            @RequestHeader(value = "X-User-Role", required = false) String roleHeader,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId,
            @PathVariable String caseNo,
            @Valid @RequestBody RiskCaseReopenRequest body) {
        Operator operator = requireAdmin(token, userIdHeader, roleHeader);
        if (operator == null) {
            return error(HttpStatus.FORBIDDEN, "无管理员权限", "RISK_FORBIDDEN", requestId);
        }
        RiskCase riskCase = riskCommandService.reopen(caseNo, operator.id(), operator.name(),
                body.expectedStatus(), body.expectedCommandStatus(), body.operationVersion(), body.reason());
        return ok("案件已重开", riskCase, requestId);
    }

    @PostMapping("/cases/{caseNo}/commands/{commandNo}/resend")
    public ResponseEntity<Map<String, Object>> resend(
            @RequestHeader(value = "Authorization", required = false) String token,
            @RequestHeader(value = "X-User-Id", required = false) String userIdHeader,
            @RequestHeader(value = "X-User-Role", required = false) String roleHeader,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId,
            @PathVariable String caseNo,
            @PathVariable String commandNo,
            @Valid @RequestBody RiskCommandResendRequest body) {
        Operator operator = requireAdmin(token, userIdHeader, roleHeader);
        if (operator == null) {
            return error(HttpStatus.FORBIDDEN, "无管理员权限", "RISK_FORBIDDEN", requestId);
        }
        RiskCase riskCase = riskCommandService.resend(caseNo, commandNo, operator.id(), operator.name(),
                body.expectedStatus(), body.expectedCommandStatus(), body.operationVersion(), body.reason());
        return ok("风控命令已重发", riskCase, requestId);
    }

    @PostMapping("/cases/{caseNo}/commands/{commandNo}/manual-complete")
    public ResponseEntity<Map<String, Object>> manualComplete(
            @RequestHeader(value = "Authorization", required = false) String token,
            @RequestHeader(value = "X-User-Id", required = false) String userIdHeader,
            @RequestHeader(value = "X-User-Role", required = false) String roleHeader,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId,
            @PathVariable String caseNo,
            @PathVariable String commandNo,
            @Valid @RequestBody RiskCommandManualCompleteRequest body) {
        Operator operator = requireAdmin(token, userIdHeader, roleHeader);
        if (operator == null) {
            return error(HttpStatus.FORBIDDEN, "无管理员权限", "RISK_FORBIDDEN", requestId);
        }
        RiskCase riskCase = riskCommandService.manualComplete(caseNo, commandNo, operator.id(), operator.name(),
                body.expectedCommandStatus(), body.result(), body.reason(), body.evidence());
        return ok("人工处理结果已登记", riskCase, requestId);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e,
                                                                HttpServletRequest request) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .findFirst()
                .orElse("请求参数不合法");
        return error(HttpStatus.BAD_REQUEST, message, "RISK_VALIDATION_FAILED", request.getHeader("X-Request-Id"));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Map<String, Object>> handleMalformedRequest(Exception e, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "请求参数格式不正确", "RISK_VALIDATION_FAILED",
                request.getHeader("X-Request-Id"));
    }

    @ExceptionHandler(RiskApiException.class)
    public ResponseEntity<Map<String, Object>> handleRiskApi(RiskApiException e, HttpServletRequest request) {
        return error(HttpStatus.valueOf(e.httpStatus()), e.getMessage(), e.errorCode(),
                request.getHeader("X-Request-Id"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception e, HttpServletRequest request) {
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "系统繁忙，请稍后重试", "RISK_SYSTEM_ERROR",
                request.getHeader("X-Request-Id"));
    }

    private ResponseEntity<Map<String, Object>> ok(String message, Object data, String requestId) {
        return body(HttpStatus.OK, message, data, null, requestId);
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String message,
                                                      String errorCode, String requestId) {
        return body(status, message, null, errorCode, requestId);
    }

    private ResponseEntity<Map<String, Object>> body(HttpStatus status, String message, Object data,
                                                     String errorCode, String requestId) {
        Map<String, Object> result = new HashMap<>();
        result.put("code", status.value());
        result.put("msg", message);
        result.put("data", data);
        if (errorCode != null) {
            result.put("errorCode", errorCode);
        }
        result.put("traceId", traceId(requestId));
        return ResponseEntity.status(status).body(result);
    }

    private String traceId(String requestId) {
        if (requestId != null && !requestId.isBlank() && requestId.length() <= 128) {
            return requestId;
        }
        return UUID.randomUUID().toString();
    }

    private Operator requireAdmin(String token, String userIdHeader, String roleHeader) {
        Long userId = parseLongHeader(userIdHeader);
        String role = roleHeader;
        if (userId == null || role == null) {
            userId = JwtUtil.parseUserId(token);
            role = JwtUtil.parseRole(token);
        }
        if (userId == null || !"ADMIN".equals(role)) {
            return null;
        }
        String nickname = null;
        JsonNode payload = JwtUtil.parsePayload(token);
        if (payload != null) {
            nickname = payload.path("nickname").asText(null);
        }
        return new Operator(userId, nickname == null || nickname.isBlank() ? "ADMIN-" + userId : nickname);
    }

    private Long parseLongHeader(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void requireOption(String name, String value, java.util.Set<String> allowed) {
        if (value != null && !allowed.contains(value)) {
            throw badRequest(name + " 不合法");
        }
    }

    private void requirePositive(String name, Long value) {
        if (value != null && value <= 0) {
            throw badRequest(name + " 必须为正整数");
        }
    }

    private RiskApiException badRequest(String message) {
        return RiskApiException.badRequest("RISK_VALIDATION_FAILED", message);
    }

    private static final java.util.Set<String> STATUSES =
            java.util.Set.of("OPEN", "PROCESSING", "RESOLVED", "CLOSED");
    private static final java.util.Set<String> SCENES = java.util.Set.of(
            "ORDER", "PAYMENT", "DELIVERY", "CONFIRM", "DISPUTE", "LISTING", "REGISTER", "LOGIN",
            "WITHDRAW", "MERCHANT");
    private static final java.util.Set<String> RISK_LEVELS =
            java.util.Set.of("LOW", "MEDIUM", "HIGH", "CRITICAL");
    private static final java.util.Set<String> COMMAND_STATUSES = java.util.Set.of(
            "NONE", "PENDING_SEND", "SENT", "SUCCESS", "FAILED", "COMMAND_FAILED", "DEAD_LETTER");
    private static final java.util.Set<String> SUBJECT_TYPES =
            java.util.Set.of("USER", "MERCHANT", "ITEM", "WITHDRAW");

    private record Operator(Long id, String name) {
    }
}
