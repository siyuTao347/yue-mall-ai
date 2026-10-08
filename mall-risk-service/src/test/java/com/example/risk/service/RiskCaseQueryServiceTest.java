package com.example.risk.service;

import api.common.PageResult;
import api.risk.RelationGraphDTO;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.risk.dto.RiskCaseDetailDTO;
import com.example.risk.dto.RiskCaseListQuery;
import com.example.risk.dto.RiskCaseSummaryDTO;
import com.example.risk.entity.RiskCase;
import com.example.risk.entity.RiskCaseNote;
import com.example.risk.entity.RiskDecision;
import com.example.risk.entity.RiskEvent;
import com.example.risk.exception.RiskApiException;
import com.example.risk.mapper.RiskCaseMapper;
import com.example.risk.mapper.RiskCaseNoteMapper;
import com.example.risk.mapper.RiskDecisionMapper;
import com.example.risk.mapper.RiskEventMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RiskCaseQueryServiceTest {
    private RiskCaseMapper caseMapper;
    private RiskCaseNoteMapper noteMapper;
    private RiskDecisionMapper decisionMapper;
    private RiskEventMapper eventMapper;
    private RiskIdentityService identityService;
    private RiskCaseQueryService service;

    @BeforeEach
    void setUp() {
        caseMapper = mock(RiskCaseMapper.class);
        noteMapper = mock(RiskCaseNoteMapper.class);
        decisionMapper = mock(RiskDecisionMapper.class);
        eventMapper = mock(RiskEventMapper.class);
        identityService = mock(RiskIdentityService.class);
        RiskDictionaryService dictionary = new RiskDictionaryService();
        ReflectionTestUtils.setField(dictionary, "relationMaxNodes", 20);
        ReflectionTestUtils.setField(dictionary, "relationMaxDepth", 1);
        service = new RiskCaseQueryService(caseMapper, noteMapper, decisionMapper, eventMapper,
                identityService, dictionary, new ObjectMapper());
    }

    @Test
    void listCasesMapsTotalAndTextFields() {
        Page<RiskCase> page = new Page<>(1, 20);
        page.setRecords(List.of(riskCase()));
        page.setTotal(1);
        when(caseMapper.selectPage(any(), any())).thenReturn(page);

        PageResult<RiskCaseSummaryDTO> result = service.listCases(
                new RiskCaseListQuery("OPEN", "ORDER", "HIGH", null, "USER", 1001L,
                        null, null, null, null), 1, 20);

        Assertions.assertEquals(1L, result.total());
        Assertions.assertEquals(1, result.records().size());
        RiskCaseSummaryDTO summary = result.records().get(0);
        Assertions.assertEquals("订单", summary.sceneText());
        Assertions.assertEquals("高", summary.riskLevelText());
        Assertions.assertEquals("待处理", summary.statusText());
    }

    @Test
    void detailMasksSensitiveEvidenceAndFlagsCompleteness() {
        when(caseMapper.selectOne(any())).thenReturn(riskCase());
        RiskDecision decision = new RiskDecision();
        decision.setDecisionNo("D1");
        decision.setEventNo("E1");
        decision.setAction("FREEZE");
        decision.setRiskLevel("HIGH");
        decision.setRiskScore(86);
        decision.setHitRulesJson("[{\"ruleCode\":\"R1\",\"ruleName\":\"规则一\",\"riskScore\":40}]");
        decision.setMissingMetricsJson("[\"MERCHANT_DISPUTE_COUNT_30D\"]");
        decision.setEvidenceJson("{\"reason\":\"多设备登录\"}");
        decision.setCreatedTime(LocalDateTime.now());
        when(decisionMapper.selectOne(any())).thenReturn(decision);

        RiskEvent event = new RiskEvent();
        event.setEventNo("E1");
        event.setScene("ORDER");
        event.setEventType("CREATE");
        event.setEventPhase("CONFIRMED");
        event.setBizType("ORDER");
        event.setBizNo("TR1");
        event.setAmount(new BigDecimal("100.00"));
        event.setIpHash("abcdef0123456789");
        event.setDeviceHash("0123456789abcdef");
        event.setPayloadJson("{\"orderNo\":\"TR1\",\"amount\":100.00,\"withdrawAccountHash\":\"secret\",\"paySecret\":\"key\"}");
        when(eventMapper.selectOne(any())).thenReturn(event);

        RiskCaseNote note = new RiskCaseNote();
        note.setId(1L);
        note.setCaseNo("CASE1");
        note.setNoteType("ASSIGN");
        note.setOperatorId(9L);
        note.setOperatorName("张三");
        note.setContent("认领案件");
        note.setEvidenceJson("{\"caseNo\":\"CASE1\"}");
        note.setCreatedTime(LocalDateTime.now());
        when(noteMapper.selectList(any())).thenReturn(List.of(note));
        when(identityService.getRelations(1001L, 1)).thenReturn(RelationGraphDTO.builder()
                .rootUserId(1001L)
                .depth(1)
                .truncated(false)
                .nodes(List.of(Map.of("userId", 1001L)))
                .edges(List.of(Map.of("sourceUserId", 1001L, "targetUserId", 2002L,
                        "relationType", "SAME_DEVICE", "weight", 80, "hitCount", 3)))
                .build());

        RiskCaseDetailDTO detail = service.detail("CASE1");

        Assertions.assertEquals("abcdef01***", detail.event().ipHashMasked());
        Assertions.assertFalse(detail.event().context().containsKey("withdrawAccountHash"));
        Assertions.assertFalse(detail.event().context().containsKey("paySecret"));
        Assertions.assertEquals("多设备登录", detail.decision().reason());
        Assertions.assertEquals(1, detail.decision().hitRules().size());
        Assertions.assertEquals("COMMAND_FAILED", detail.command().status());
        Assertions.assertEquals("执行失败", detail.command().statusText());
        Assertions.assertEquals("COMMAND_FAILED", detail.command().rawStatus());
        Assertions.assertEquals(2, detail.relations().size());
        Assertions.assertTrue(detail.evidenceCompleteness().get("decision"));
        Assertions.assertTrue(detail.evidenceCompleteness().get("event"));
    }

    @Test
    void detailMapsDeadLetterNoteToDisplayStatus() {
        when(caseMapper.selectOne(any())).thenReturn(riskCase());
        when(eventMapper.selectOne(any())).thenReturn(null);
        RiskCaseNote deadLetter = new RiskCaseNote();
        deadLetter.setId(9L);
        deadLetter.setCaseNo("CASE1");
        deadLetter.setNoteType("COMMAND_DEAD_LETTER");
        deadLetter.setOperatorId(0L);
        deadLetter.setOperatorName("RISK_COMMAND_SYSTEM");
        deadLetter.setContent("命令重试耗尽，进入死信队列");
        deadLetter.setEvidenceJson("{\"commandNo\":\"CMD1\"}");
        deadLetter.setCreatedTime(LocalDateTime.now());
        when(noteMapper.selectList(any())).thenReturn(List.of(deadLetter));

        RiskCaseDetailDTO detail = service.detail("CASE1");

        Assertions.assertEquals("DEAD_LETTER", detail.command().status());
        Assertions.assertEquals("COMMAND_FAILED", detail.command().rawStatus());
        Assertions.assertEquals("死信待人工处理", detail.command().statusText());
    }

    @Test
    void detailMissingCaseThrowsStableError() {
        when(caseMapper.selectOne(any())).thenReturn(null);

        RiskApiException error = Assertions.assertThrows(RiskApiException.class,
                () -> service.detail("MISSING"));

        Assertions.assertEquals(RiskApiException.CASE_NOT_FOUND, error.errorCode());
        Assertions.assertEquals(404, error.httpStatus());
    }

    private RiskCase riskCase() {
        RiskCase riskCase = new RiskCase();
        riskCase.setId(1L);
        riskCase.setCaseNo("CASE1");
        riskCase.setDecisionNo("D1");
        riskCase.setScene("ORDER");
        riskCase.setBizType("ORDER");
        riskCase.setBizNo("TR1");
        riskCase.setSubjectType("USER");
        riskCase.setSubjectId(1001L);
        riskCase.setRiskLevel("HIGH");
        riskCase.setRiskScore(86);
        riskCase.setStatus("OPEN");
        riskCase.setCommandStatus("COMMAND_FAILED");
        riskCase.setLastCommandNo("CMD1");
        riskCase.setResolvedAction("FREEZE");
        riskCase.setCommandRetryCount(3);
        riskCase.setCommandLastError("下游服务暂时不可用");
        riskCase.setOperationVersion(5L);
        riskCase.setReopenCount(0);
        riskCase.setCreatedTime(LocalDateTime.now());
        riskCase.setUpdatedTime(LocalDateTime.now());
        return riskCase;
    }
}
