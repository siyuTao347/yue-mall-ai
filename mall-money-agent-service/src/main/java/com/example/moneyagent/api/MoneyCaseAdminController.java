package com.example.moneyagent.api;

import api.response.ApiResponse;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.moneyagent.application.MoneyCaseService;
import com.example.moneyagent.persistence.entity.MoneyCaseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/money-agent")
public class MoneyCaseAdminController {

    private final MoneyCaseService caseService;

    public MoneyCaseAdminController(MoneyCaseService caseService) {
        this.caseService = caseService;
    }

    @GetMapping("/cases")
    public ApiResponse<Page<MoneyCaseEntity>> listCases(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String problemFamily,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String severity) {
        Page<MoneyCaseEntity> result = caseService.listCases(page, size, problemFamily, state, severity);
        return ApiResponse.success(result);
    }

    @GetMapping("/cases/{caseNo}")
    public ApiResponse<MoneyCaseService.CaseDetailResult> getCaseDetail(@PathVariable String caseNo) {
        MoneyCaseService.CaseDetailResult detail = caseService.getCaseDetail(caseNo);
        if (detail == null) {
            return ApiResponse.error(404, "NOT_FOUND", "案件不存在: " + caseNo);
        }
        return ApiResponse.success(detail);
    }

    @PostMapping("/cases/investigate")
    public ApiResponse<MoneyCaseService.CaseExecutionResult> startInvestigation(@RequestBody Map<String, Object> req) {
        String scopeKey = (String) req.get("scopeKey");
        String problemFamily = (String) req.getOrDefault("problemFamily", "PAYMENT_CONFIRMATION");
        String targetRef = (String) req.get("targetRef");
        String origin = (String) req.getOrDefault("origin", "OPERATIONS");
        Number staffIdNum = (Number) req.get("staffId");
        Long staffId = staffIdNum != null ? staffIdNum.longValue() : 1L;

        MoneyCaseService.CaseExecutionResult result = caseService.createOrAttachCase(
                scopeKey, problemFamily, targetRef, origin, staffId, "FINANCE");
        return ApiResponse.success(result);
    }

    @PostMapping("/cases/{caseNo}/close")
    public ApiResponse<Boolean> closeCase(@PathVariable String caseNo, @RequestBody Map<String, Object> req) {
        String reason = (String) req.getOrDefault("reason", "人工结案");
        Number staffIdNum = (Number) req.get("staffId");
        Long staffId = staffIdNum != null ? staffIdNum.longValue() : 1L;

        boolean success = caseService.closeCase(caseNo, reason, staffId);
        return ApiResponse.success(success);
    }
}
