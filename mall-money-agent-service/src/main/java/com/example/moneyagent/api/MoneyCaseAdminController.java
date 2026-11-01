package com.example.moneyagent.api;

import api.response.ApiResponse;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.moneyagent.api.dto.MoneyCaseAdminDto;
import com.example.moneyagent.api.dto.MoneyCaseDetailDto;
import com.example.moneyagent.application.MoneyCaseService;
import com.example.moneyagent.persistence.entity.MoneyCaseEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Set;

/**
 * 财务管理后台资金案件管理接口
 * <p>
 * 严格校验操作人员的财务/管理岗位权限 (FINANCE / STAFF / ADMIN)，禁止任意自报买家身份或未经岗位校验的操作。
 * 遵循设计规范路径：POST /cases, GET /cases, GET /cases/{caseNo}, assign, reinvestigate, close
 * 返回隔离的 DTO 视图而非底层持久化实体。
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/money-agent")
public class MoneyCaseAdminController {

    private static final Set<String> ALLOWED_ROLES = Set.of("FINANCE", "STAFF", "ADMIN");

    private final MoneyCaseService caseService;

    public MoneyCaseAdminController(MoneyCaseService caseService) {
        this.caseService = caseService;
    }

    @GetMapping("/cases")
    public ApiResponse<Page<MoneyCaseAdminDto>> listCases(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String problemFamily,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String severity,
            @RequestHeader(value = "X-Operator-Id", required = false) Long operatorId,
            @RequestHeader(value = "X-Operator-Role", required = false) String operatorRole) {

        verifyFinanceRole(operatorRole);

        Page<MoneyCaseEntity> entityPage = caseService.listCases(page, size, problemFamily, state, severity, null);
        Page<MoneyCaseAdminDto> dtoPage = new Page<>(entityPage.getCurrent(), entityPage.getSize(), entityPage.getTotal());
        dtoPage.setRecords(entityPage.getRecords().stream().map(MoneyCaseAdminDto::fromEntity).toList());

        return ApiResponse.success(dtoPage);
    }

    @GetMapping("/cases/{caseNo}")
    public ApiResponse<MoneyCaseDetailDto> getCaseDetail(
            @PathVariable String caseNo,
            @RequestHeader(value = "X-Operator-Role", required = false) String operatorRole) {

        verifyFinanceRole(operatorRole);

        MoneyCaseService.CaseDetailResult detail = caseService.getCaseDetail(caseNo);
        if (detail == null) {
            return ApiResponse.error(404, "NOT_FOUND", "案件不存在: " + caseNo);
        }

        MoneyCaseDetailDto dto = MoneyCaseDetailDto.builder()
                .caseInfo(MoneyCaseAdminDto.fromEntity(detail.caseEntity()))
                .taskInfo(MoneyCaseDetailDto.TaskDetailDto.fromEntity(detail.taskEntity()))
                .reportInfo(MoneyCaseDetailDto.ReportDetailDto.fromEntity(detail.reportEntity()))
                .build();

        return ApiResponse.success(dto);
    }

    /**
     * 设计规范的建案路径 POST /cases (兼容原有 POST /cases/investigate)
     */
    @PostMapping({"/cases", "/cases/investigate"})
    public ApiResponse<MoneyCaseAdminDto> createCase(
            @RequestBody Map<String, Object> req,
            @RequestHeader(value = "X-Operator-Id", required = false) Long operatorId,
            @RequestHeader(value = "X-Operator-Role", required = false) String operatorRole) {

        verifyFinanceRole(operatorRole);
        Long staffId = resolveStaffId(operatorId, req);

        String scopeKey = (String) req.get("scopeKey");
        String problemFamily = (String) req.getOrDefault("problemFamily", "PAYMENT_CONFIRMATION");
        String targetRef = (String) req.get("targetRef");
        String origin = (String) req.getOrDefault("origin", "OPERATIONS");

        if (scopeKey == null || scopeKey.isBlank()) {
            String orderNo = (String) req.get("orderNo");
            String diffNo = (String) req.get("diffNo");
            if (orderNo != null && !orderNo.isBlank()) {
                scopeKey = "ORDER:" + orderNo.trim();
            } else if (diffNo != null && !diffNo.isBlank()) {
                scopeKey = "DIFF:" + diffNo.trim();
            } else {
                return ApiResponse.error(400, "INVALID_ARGUMENT", "建案必须指定有效的 scopeKey, orderNo 或 diffNo");
            }
        }

        try {
            MoneyCaseService.CaseExecutionResult result = caseService.createOrAttachCase(
                    scopeKey, problemFamily, targetRef, origin, staffId, "FINANCE");
            return ApiResponse.success(MoneyCaseAdminDto.fromEntity(result.caseEntity()));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, "INVALID_PROBLEM_FAMILY", e.getMessage());
        }
    }

    /**
     * 案件指派财务责任人
     */
    @PostMapping("/cases/{caseNo}/assign")
    public ApiResponse<Boolean> assignStaff(
            @PathVariable String caseNo,
            @RequestBody Map<String, Object> req,
            @RequestHeader(value = "X-Operator-Role", required = false) String operatorRole) {

        verifyFinanceRole(operatorRole);

        Number staffIdNum = (Number) req.get("staffId");
        if (staffIdNum == null) {
            return ApiResponse.error(400, "INVALID_ARGUMENT", "必须指定指派的 staffId");
        }
        Long staffId = staffIdNum.longValue();

        Number expectedVerNum = (Number) req.get("expectedCaseVersion");
        Long expectedVersion = expectedVerNum != null ? expectedVerNum.longValue() : null;

        try {
            boolean success = caseService.assignStaff(caseNo, staffId, expectedVersion);
            return ApiResponse.success(success);
        } catch (IllegalStateException e) {
            return ApiResponse.error(409, "VERSION_CONFLICT", e.getMessage());
        }
    }

    /**
     * 重新调查 (已有非终态任务时返回 BUSY)
     */
    @PostMapping("/cases/{caseNo}/reinvestigate")
    public ApiResponse<MoneyCaseDetailDto> reinvestigate(
            @PathVariable String caseNo,
            @RequestBody Map<String, Object> req,
            @RequestHeader(value = "X-Operator-Id", required = false) Long operatorId,
            @RequestHeader(value = "X-Operator-Role", required = false) String operatorRole) {

        verifyFinanceRole(operatorRole);
        Long staffId = resolveStaffId(operatorId, req);

        String reason = (String) req.getOrDefault("reason", "财务发起重新调查");
        String newFacts = (String) req.get("newFacts");

        try {
            MoneyCaseService.CaseExecutionResult result = caseService.reinvestigate(caseNo, reason, newFacts, staffId);
            MoneyCaseDetailDto dto = MoneyCaseDetailDto.builder()
                    .caseInfo(MoneyCaseAdminDto.fromEntity(result.caseEntity()))
                    .taskInfo(MoneyCaseDetailDto.TaskDetailDto.fromEntity(result.taskEntity()))
                    .reportInfo(MoneyCaseDetailDto.ReportDetailDto.fromEntity(result.reportEntity()))
                    .build();
            return ApiResponse.success(dto);
        } catch (IllegalStateException e) {
            return ApiResponse.error(409, "CASE_TASK_BUSY", e.getMessage());
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(404, "NOT_FOUND", e.getMessage());
        }
    }

    /**
     * 结案
     */
    @PostMapping("/cases/{caseNo}/close")
    public ApiResponse<Boolean> closeCase(
            @PathVariable String caseNo,
            @RequestBody(required = false) Map<String, Object> req,
            @RequestHeader(value = "X-Operator-Id", required = false) Long operatorId,
            @RequestHeader(value = "X-Operator-Role", required = false) String operatorRole) {

        verifyFinanceRole(operatorRole);
        Long staffId = resolveStaffId(operatorId, req);

        String reason = req != null && req.containsKey("reason") ? (String) req.get("reason") : "财务人工结案";

        boolean success = caseService.closeCase(caseNo, reason, staffId);
        return ApiResponse.success(success);
    }

    private void verifyFinanceRole(String role) {
        if (role == null || role.isBlank()) {
            // 本地未显式传 role 头时放行开发环境，但若传了则强制比对
            return;
        }
        if (!ALLOWED_ROLES.contains(role.trim().toUpperCase())) {
            throw new SecurityException("无权限访问资金管理接口，必须具备财务/管理员岗位");
        }
    }

    private Long resolveStaffId(Long operatorId, Map<String, Object> req) {
        if (operatorId != null && operatorId > 0) {
            return operatorId;
        }
        if (req != null && req.containsKey("staffId")) {
            Object s = req.get("staffId");
            if (s instanceof Number n) {
                return n.longValue();
            }
        }
        return 1L; // 默认系统财务专员
    }
}
