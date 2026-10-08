package com.example.risk.controller;

import api.common.PageResult;
import com.example.risk.dto.RiskCaseSummaryDTO;
import com.example.risk.dto.RiskDictionaryDTO;
import com.example.risk.exception.RiskApiException;
import com.example.risk.service.RiskCaseQueryService;
import com.example.risk.service.RiskCommandService;
import com.example.risk.service.RiskDictionaryService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RiskCaseAdminControllerTest {
    private RiskCaseQueryService queryService;
    private RiskCommandService riskCommandService;
    private RiskDictionaryService dictionaryService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        queryService = mock(RiskCaseQueryService.class);
        riskCommandService = mock(RiskCommandService.class);
        dictionaryService = mock(RiskDictionaryService.class);
        RiskCaseAdminController controller = new RiskCaseAdminController(queryService, riskCommandService,
                dictionaryService, new SimpleMeterRegistry());
        ReflectionTestUtils.setField(controller, "defaultPageSize", 20);
        ReflectionTestUtils.setField(controller, "maxPageSize", 100);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void listRejectsNonAdminWithoutLeakingData() throws Exception {
        mockMvc.perform(get("/api/admin/risk/cases"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.errorCode").value("RISK_FORBIDDEN"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void listRejectsOutOfRangePageSize() throws Exception {
        mockMvc.perform(admin(get("/api/admin/risk/cases")).param("pageSize", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("RISK_VALIDATION_FAILED"));
    }

    @Test
    void listRejectsUnknownStatus() throws Exception {
        mockMvc.perform(admin(get("/api/admin/risk/cases")).param("status", "HACKED"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("RISK_VALIDATION_FAILED"));
    }

    @Test
    void listReturnsPagedRecords() throws Exception {
        when(queryService.listCases(any(), anyInt(), anyInt()))
                .thenReturn(PageResult.of(List.<RiskCaseSummaryDTO>of(), 0L, 1, 20));

        mockMvc.perform(admin(get("/api/admin/risk/cases")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.records").isArray());
    }

    @Test
    void dictionariesReturnCommandPolicy() throws Exception {
        when(dictionaryService.dictionary()).thenReturn(new RiskDictionaryDTO(
                List.of(), List.of(), List.of(), List.of(), List.of(), null));

        mockMvc.perform(admin(get("/api/admin/risk/dictionaries")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void assignConflictReturnsStableStateChangedCodeAndTraceId() throws Exception {
        when(riskCommandService.assign(any(), any(), any(), any(), any()))
                .thenThrow(RiskApiException.stateChanged());

        mockMvc.perform(admin(post("/api/admin/risk/cases/CASE1/assign"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedStatus\":\"OPEN\",\"operationVersion\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("RISK_CASE_STATE_CHANGED"))
                .andExpect(jsonPath("$.traceId").value("trace-risk-1"));
    }

    @Test
    void resolveValidatesReasonLength() throws Exception {
        mockMvc.perform(admin(post("/api/admin/risk/cases/CASE1/resolve"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedStatus\":\"PROCESSING\",\"operationVersion\":1,"
                                + "\"command\":\"FREEZE\",\"reason\":\"too short\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("RISK_VALIDATION_FAILED"));
    }

    @Test
    void resolveMapsMissingCaseToStableNotFoundCode() throws Exception {
        when(riskCommandService.resolve(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(RiskApiException.notFound(RiskApiException.CASE_NOT_FOUND, "风控案件不存在"));

        mockMvc.perform(admin(post("/api/admin/risk/cases/MISSING/resolve"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedStatus\":\"PROCESSING\",\"operationVersion\":1,"
                                + "\"command\":\"FREEZE\",\"reason\":\"命中规则需要冻结处理\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("RISK_CASE_NOT_FOUND"));
    }

    private MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder builder) {
        return builder
                .header("X-User-Id", "9")
                .header("X-User-Role", "ADMIN")
                .header("X-Request-Id", "trace-risk-1");
    }
}
