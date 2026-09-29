package com.example.risk.service;

import api.risk.RiskCommandResultDTO;
import com.example.risk.entity.RiskCase;
import com.example.risk.entity.RiskCaseNote;
import com.example.risk.mapper.RiskCaseMapper;
import com.example.risk.mapper.RiskCaseNoteMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.annotations.Update;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;

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
    private RiskCommandService service;

    @BeforeEach
    void setUp() {
        caseMapper = mock(RiskCaseMapper.class);
        noteMapper = mock(RiskCaseNoteMapper.class);
        RocketMQTemplate rocketMQTemplate = mock(RocketMQTemplate.class);
        service = new RiskCommandService(caseMapper, noteMapper, rocketMQTemplate,
                new ObjectMapper());
    }

    @Test
    void commandSuccessClosesCaseAndWritesOneAuditNote() {
        when(caseMapper.selectOne(any())).thenReturn(riskCase());
        when(caseMapper.markCommandSuccess(eq(1L), eq("CMD1"), any(LocalDateTime.class)))
                .thenReturn(1, 0);

        service.confirmCommand(result(true, true));
        service.confirmCommand(result(true, true));

        ArgumentCaptor<RiskCaseNote> noteCaptor = ArgumentCaptor.forClass(RiskCaseNote.class);
        verify(noteMapper, times(1)).insert(noteCaptor.capture());
        Assertions.assertEquals("COMMAND_SUCCESS", noteCaptor.getValue().getNoteType());
        Assertions.assertEquals("RESOLVED", noteCaptor.getValue().getBeforeStatus());
        Assertions.assertEquals("CLOSED", noteCaptor.getValue().getAfterStatus());
    }

    @Test
    void commandFailureMarksFailedAndKeepsCaseResolved() {
        when(caseMapper.selectOne(any())).thenReturn(riskCase());
        when(caseMapper.markCommandExecutionFailed(eq(1L), eq("CMD1"), any(LocalDateTime.class)))
                .thenReturn(1);

        service.confirmCommand(result(false, false));

        verify(caseMapper).markCommandExecutionFailed(eq(1L), eq("CMD1"), any(LocalDateTime.class));
        ArgumentCaptor<RiskCaseNote> noteCaptor = ArgumentCaptor.forClass(RiskCaseNote.class);
        verify(noteMapper).insert(noteCaptor.capture());
        Assertions.assertEquals("COMMAND_FAILED", noteCaptor.getValue().getNoteType());
        Assertions.assertEquals("RESOLVED", noteCaptor.getValue().getBeforeStatus());
        Assertions.assertEquals("RESOLVED", noteCaptor.getValue().getAfterStatus());
    }

    @Test
    void deadLetterMarksCommandFailedWithoutClosingCase() {
        when(caseMapper.selectOne(any())).thenReturn(riskCase());
        when(caseMapper.markCommandDeadLetter(eq(1L), eq("CMD1"), any(LocalDateTime.class)))
                .thenReturn(1);

        service.markDeadLetter("CMD1");

        verify(caseMapper).markCommandDeadLetter(eq(1L), eq("CMD1"), any(LocalDateTime.class));
        verify(caseMapper, never()).close(eq(1L), any(LocalDateTime.class));
        ArgumentCaptor<RiskCaseNote> noteCaptor = ArgumentCaptor.forClass(RiskCaseNote.class);
        verify(noteMapper).insert(noteCaptor.capture());
        Assertions.assertEquals("COMMAND_DEAD_LETTER", noteCaptor.getValue().getNoteType());
    }

    @Test
    void sendFailureCannotOverwriteDeadLetterStatus() throws NoSuchMethodException {
        Update update = RiskCaseMapper.class
                .getMethod("markCommandFailed", Long.class, String.class, LocalDateTime.class)
                .getAnnotation(Update.class);

        Assertions.assertNotNull(update);
        Assertions.assertTrue(update.value()[0]
                .contains("command_status IN ('PENDING_SEND', 'SENT')"));
        Assertions.assertFalse(update.value()[0].contains("COMMAND_FAILED"));
    }

    private RiskCase riskCase() {
        RiskCase riskCase = new RiskCase();
        riskCase.setId(1L);
        riskCase.setCaseNo("CASE1");
        riskCase.setScene("ORDER");
        riskCase.setStatus("RESOLVED");
        riskCase.setLastCommandNo("CMD1");
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
