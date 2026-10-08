package com.example.risk.service;

import api.risk.RiskCommandResultDTO;
import com.example.risk.entity.RiskCase;
import com.example.risk.entity.RiskCaseNote;
import com.example.risk.exception.RiskApiException;
import com.example.risk.mapper.RiskCaseMapper;
import com.example.risk.mapper.RiskCaseNoteMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.ibatis.annotations.Update;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RiskCommandServiceTest {
    private RiskCaseMapper caseMapper;
    private RiskCaseNoteMapper noteMapper;
    private RiskDictionaryService dictionary;
    private RiskCommandService service;

    @BeforeEach
    void setUp() {
        caseMapper = mock(RiskCaseMapper.class);
        noteMapper = mock(RiskCaseNoteMapper.class);
        RocketMQTemplate rocketMQTemplate = mock(RocketMQTemplate.class);
        dictionary = new RiskDictionaryService();
        ReflectionTestUtils.setField(dictionary, "manualCompleteEnabled", true);
        service = new RiskCommandService(caseMapper, noteMapper, rocketMQTemplate,
                new ObjectMapper(), dictionary, new SimpleMeterRegistry());
    }

    @Test
    void commandSuccessKeepsCaseResolvedAndWritesOneAuditNote() {
        when(caseMapper.selectOne(any())).thenReturn(riskCase());
        when(caseMapper.markCommandSuccess(eq(1L), eq("CMD1"), any(LocalDateTime.class)))
                .thenReturn(1, 0);

        service.confirmCommand(result(true, true));
        service.confirmCommand(result(true, true));

        ArgumentCaptor<RiskCaseNote> noteCaptor = ArgumentCaptor.forClass(RiskCaseNote.class);
        verify(noteMapper, times(1)).insert(noteCaptor.capture());
        Assertions.assertEquals("COMMAND_SUCCESS", noteCaptor.getValue().getNoteType());
        Assertions.assertEquals("RESOLVED", noteCaptor.getValue().getBeforeStatus());
        Assertions.assertEquals("RESOLVED", noteCaptor.getValue().getAfterStatus());
    }

    @Test
    void commandFailureMarksFailedAndKeepsCaseResolved() {
        when(caseMapper.selectOne(any())).thenReturn(riskCase());
        when(caseMapper.markCommandExecutionFailed(eq(1L), eq("CMD1"), any(), any(LocalDateTime.class)))
                .thenReturn(1);

        service.confirmCommand(result(false, false));

        verify(caseMapper).markCommandExecutionFailed(eq(1L), eq("CMD1"), any(), any(LocalDateTime.class));
        ArgumentCaptor<RiskCaseNote> noteCaptor = ArgumentCaptor.forClass(RiskCaseNote.class);
        verify(noteMapper).insert(noteCaptor.capture());
        Assertions.assertEquals("COMMAND_FAILED", noteCaptor.getValue().getNoteType());
        Assertions.assertEquals("RESOLVED", noteCaptor.getValue().getBeforeStatus());
        Assertions.assertEquals("RESOLVED", noteCaptor.getValue().getAfterStatus());
    }

    @Test
    void deadLetterMarksCommandFailedWithoutClosingCase() {
        when(caseMapper.selectOne(any())).thenReturn(riskCase());
        when(caseMapper.markCommandDeadLetter(eq(1L), eq("CMD1"), any(), any(LocalDateTime.class)))
                .thenReturn(1);

        service.markDeadLetter("CMD1");

        verify(caseMapper).markCommandDeadLetter(eq(1L), eq("CMD1"), any(), any(LocalDateTime.class));
        verify(caseMapper, never()).closeVersioned(any(), any(), any(), any(), any());
        ArgumentCaptor<RiskCaseNote> noteCaptor = ArgumentCaptor.forClass(RiskCaseNote.class);
        verify(noteMapper).insert(noteCaptor.capture());
        Assertions.assertEquals("COMMAND_DEAD_LETTER", noteCaptor.getValue().getNoteType());
    }

    @Test
    void executionFailureUsesCommandFailedStatus() throws NoSuchMethodException {
        Update update = RiskCaseMapper.class
                .getMethod("markCommandExecutionFailed", Long.class, String.class, String.class, LocalDateTime.class)
                .getAnnotation(Update.class);

        Assertions.assertNotNull(update);
        Assertions.assertTrue(update.value()[0].contains("command_status = 'COMMAND_FAILED'"));
        Assertions.assertFalse(update.value()[0].contains("command_status = 'FAILED'"));
    }

    @Test
    void successCanOverwriteExecutionFailure() throws NoSuchMethodException {
        Update update = RiskCaseMapper.class
                .getMethod("markCommandSuccess", Long.class, String.class, LocalDateTime.class)
                .getAnnotation(Update.class);

        Assertions.assertNotNull(update);
        Assertions.assertTrue(update.value()[0].contains("'COMMAND_FAILED'"));
    }

    @Test
    void sendFailureCannotOverwriteDeadLetterStatus() throws NoSuchMethodException {
        Update update = RiskCaseMapper.class
                .getMethod("markCommandFailed", Long.class, String.class, String.class, LocalDateTime.class)
                .getAnnotation(Update.class);

        Assertions.assertNotNull(update);
        Assertions.assertTrue(update.value()[0]
                .contains("command_status IN ('PENDING_SEND', 'SENT')"));
        Assertions.assertFalse(update.value()[0].contains("COMMAND_FAILED"));
    }

    @Test
    void assignRejectsConcurrentChangeWithStableErrorCode() {
        when(caseMapper.selectOne(any())).thenReturn(openCase());
        when(caseMapper.assignVersioned(eq(1L), eq(9L), eq("OPEN"), eq(3L), any(LocalDateTime.class)))
                .thenReturn(0);

        RiskApiException error = Assertions.assertThrows(RiskApiException.class,
                () -> service.assign("CASE1", 9L, "张三", "OPEN", 3L));

        Assertions.assertEquals(RiskApiException.STATE_CHANGED, error.errorCode());
        Assertions.assertEquals(409, error.httpStatus());
        verify(noteMapper, never()).insert(any(RiskCaseNote.class));
    }

    @Test
    void assignWritesAuditNoteOnSuccess() {
        RiskCase assigned = openCase();
        assigned.setStatus("PROCESSING");
        assigned.setOperationVersion(4L);
        when(caseMapper.selectOne(any())).thenReturn(openCase(), assigned);
        when(caseMapper.assignVersioned(eq(1L), eq(9L), eq("OPEN"), eq(3L), any(LocalDateTime.class)))
                .thenReturn(1);

        RiskCase result = service.assign("CASE1", 9L, "张三", "OPEN", 3L);

        Assertions.assertEquals("PROCESSING", result.getStatus());
        ArgumentCaptor<RiskCaseNote> noteCaptor = ArgumentCaptor.forClass(RiskCaseNote.class);
        verify(noteMapper).insert(noteCaptor.capture());
        Assertions.assertEquals("ASSIGN", noteCaptor.getValue().getNoteType());
        Assertions.assertEquals("PROCESSING", noteCaptor.getValue().getAfterStatus());
    }

    @Test
    void resolveRejectsCommandNotAllowedByScene() {
        RiskCase processing = openCase();
        processing.setStatus("PROCESSING");
        when(caseMapper.selectOne(any())).thenReturn(processing);

        RiskApiException error = Assertions.assertThrows(RiskApiException.class,
                () -> service.resolve("CASE1", 9L, "张三", "PROCESSING", 3L, "REJECT",
                        "命中规则需要驳回处理", Map.of()));

        Assertions.assertEquals(RiskApiException.INVALID_OPERATION, error.errorCode());
        verify(caseMapper, never()).resolveVersioned(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void resolveRejectsUnknownActionParam() {
        RiskCase processing = openCase();
        processing.setStatus("PROCESSING");
        when(caseMapper.selectOne(any())).thenReturn(processing);

        RiskApiException error = Assertions.assertThrows(RiskApiException.class,
                () -> service.resolve("CASE1", 9L, "张三", "PROCESSING", 3L, "FREEZE",
                        "命中规则需要冻结处理", Map.of("rawPayload", "{...}")));

        Assertions.assertEquals(RiskApiException.INVALID_OPERATION, error.errorCode());
    }

    @Test
    void resolveRejectsDelaySettleWithoutRequiredParam() {
        RiskCase processing = openCase();
        processing.setStatus("PROCESSING");
        when(caseMapper.selectOne(any())).thenReturn(processing);

        RiskApiException error = Assertions.assertThrows(RiskApiException.class,
                () -> service.resolve("CASE1", 9L, "张三", "PROCESSING", 3L, "DELAY_SETTLE",
                        "结算风险较高需要延迟结算", Map.of("remark", "人工确认")));

        Assertions.assertEquals(RiskApiException.INVALID_OPERATION, error.errorCode());
    }

    @Test
    void manualCompleteRejectsWhenFeatureDisabled() {
        ReflectionTestUtils.setField(dictionary, "manualCompleteEnabled", false);
        RiskCase failed = riskCase();
        failed.setCommandStatus("COMMAND_FAILED");
        when(caseMapper.selectOne(any())).thenReturn(failed);

        RiskApiException error = Assertions.assertThrows(RiskApiException.class,
                () -> service.manualComplete("CASE1", "CMD1", 9L, "张三", "COMMAND_FAILED",
                        "SUCCESS", "已在业务系统人工完成处理", "工单记录"));

        Assertions.assertEquals(RiskApiException.MANUAL_COMPLETE_DISABLED, error.errorCode());
        Assertions.assertEquals(403, error.httpStatus());
    }

    @Test
    void manualCompleteRegistersResultAndAuditNote() {
        RiskCase failed = riskCase();
        failed.setCommandStatus("COMMAND_FAILED");
        when(caseMapper.selectOne(any())).thenReturn(failed);
        when(caseMapper.manualComplete(eq(1L), eq("CMD1"), eq("COMMAND_FAILED"), eq("SUCCESS"),
                any(), any(LocalDateTime.class))).thenReturn(1);

        service.manualComplete("CASE1", "CMD1", 9L, "张三", "COMMAND_FAILED",
                "SUCCESS", "已在业务系统人工完成处理", "工单记录");

        verify(caseMapper).manualComplete(eq(1L), eq("CMD1"), eq("COMMAND_FAILED"), eq("SUCCESS"),
                any(), any(LocalDateTime.class));
        ArgumentCaptor<RiskCaseNote> noteCaptor = ArgumentCaptor.forClass(RiskCaseNote.class);
        verify(noteMapper).insert(noteCaptor.capture());
        Assertions.assertEquals("COMMAND_MANUAL_COMPLETE", noteCaptor.getValue().getNoteType());
    }

    private RiskCase riskCase() {
        RiskCase riskCase = new RiskCase();
        riskCase.setId(1L);
        riskCase.setCaseNo("CASE1");
        riskCase.setScene("ORDER");
        riskCase.setStatus("RESOLVED");
        riskCase.setCommandStatus("SENT");
        riskCase.setOperationVersion(2L);
        riskCase.setLastCommandNo("CMD1");
        return riskCase;
    }

    private RiskCase openCase() {
        RiskCase riskCase = new RiskCase();
        riskCase.setId(1L);
        riskCase.setCaseNo("CASE1");
        riskCase.setScene("ORDER");
        riskCase.setStatus("OPEN");
        riskCase.setCommandStatus("NONE");
        riskCase.setOperationVersion(3L);
        return riskCase;
    }

    private RiskCommandResultDTO result(boolean success, boolean updated) {
        return RiskCommandResultDTO.builder()
                .commandNo("CMD1")
                .caseNo("CASE1")
                .scene("ORDER")
                .success(success)
                .updated(updated)
                .message(success ? null : "业务条件更新失败")
                .build();
    }
}
