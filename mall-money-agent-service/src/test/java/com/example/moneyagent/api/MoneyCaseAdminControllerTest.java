package com.example.moneyagent.api;

import api.response.ApiResponse;
import com.example.moneyagent.api.dto.MoneyCaseAdminDto;
import com.example.moneyagent.api.dto.MoneyCaseDetailDto;
import com.example.moneyagent.application.MoneyCaseService;
import com.example.moneyagent.persistence.entity.MoneyCaseEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MoneyCaseAdminControllerTest {

    private MoneyCaseService caseService;
    private MoneyCaseAdminController controller;

    @BeforeEach
    void setUp() {
        caseService = mock(MoneyCaseService.class);
        controller = new MoneyCaseAdminController(caseService);
    }

    @Test
    @DisplayName("测试管理接口必须具备财务或管理角色")
    void testRoleCheck() {
        assertThrows(SecurityException.class, () ->
                controller.listCases(1, 10, null, null, null, 100L, "BUYER"));

        assertThrows(SecurityException.class, () ->
                controller.getCaseDetail("MC001", "GUEST"));
    }

    @Test
    @DisplayName("测试 POST /cases 成功建案并返回 DTO")
    void testCreateCaseAdmin() {
        MoneyCaseEntity caseEntity = MoneyCaseEntity.builder()
                .id(1L)
                .caseNo("MC0123456789ABCDEF0123456789ABCDEF")
                .scopeKey("ORDER:T1001")
                .orderNo("T1001")
                .problemFamily("PAYMENT_CONFIRMATION")
                .origin("OPERATIONS")
                .state("OPEN")
                .outcome("UNDETERMINED")
                .ownerStaffId(88L)
                .version(1L)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        MoneyCaseService.CaseExecutionResult execResult =
                new MoneyCaseService.CaseExecutionResult(caseEntity, null, null, true);

        when(caseService.createOrAttachCase(any(), any(), any(), any(), any(), any()))
                .thenReturn(execResult);

        Map<String, Object> req = Map.of(
                "orderNo", "T1001",
                "problemFamily", "PAYMENT_CONFIRMATION",
                "staffId", 88
        );

        ApiResponse<MoneyCaseAdminDto> response = controller.createCase(req, 88L, "FINANCE");
        assertNotNull(response);
        assertEquals(200, response.code());
        assertNotNull(response.data());
        assertEquals("MC0123456789ABCDEF0123456789ABCDEF", response.data().getCaseNo());
        assertEquals(88L, response.data().getOwnerStaffId());
    }

    @Test
    @DisplayName("测试 POST /cases/{caseNo}/assign 成功分配责任人")
    void testAssignStaff() {
        when(caseService.assignStaff("MC001", 99L, 1L)).thenReturn(true);

        Map<String, Object> req = Map.of(
                "staffId", 99,
                "expectedCaseVersion", 1
        );

        ApiResponse<Boolean> response = controller.assignStaff("MC001", req, "FINANCE");
        assertTrue(response.data());
    }

    @Test
    @DisplayName("测试 POST /cases/{caseNo}/reinvestigate 任务忙时返回 409 CASE_TASK_BUSY")
    void testReinvestigateBusy() {
        when(caseService.reinvestigate(eq("MC001"), any(), any(), any()))
                .thenThrow(new IllegalStateException("CASE_TASK_BUSY: 当前已有执行中的调查任务"));

        Map<String, Object> req = Map.of("reason", "客户提供新证据");
        ApiResponse<MoneyCaseDetailDto> response = controller.reinvestigate("MC001", req, 88L, "FINANCE");
        assertEquals(409, response.code());
        assertEquals("CASE_TASK_BUSY", response.errorCode());
    }
}
