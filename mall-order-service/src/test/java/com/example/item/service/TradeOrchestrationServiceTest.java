package com.example.item.service;

import com.example.item.config.TradeOrchestrationProperties;
import com.example.item.entity.TradeOrchestrationStep;
import com.example.item.entity.TradeOrchestrationTask;
import com.example.item.mapper.TradeOrchestrationStepMapper;
import com.example.item.mapper.TradeOrchestrationTaskMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TradeOrchestrationServiceTest {
    private TradeOrchestrationTaskMapper taskMapper;
    private TradeOrchestrationStepMapper stepMapper;
    private TradeOrchestrationCommandService commandService;
    private TradeOrchestrationPublisher publisher;
    private TradeOrchestrationService service;

    @BeforeEach
    void setUp() {
        taskMapper = mock(TradeOrchestrationTaskMapper.class);
        stepMapper = mock(TradeOrchestrationStepMapper.class);
        commandService = mock(TradeOrchestrationCommandService.class);
        publisher = mock(TradeOrchestrationPublisher.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        doAnswer(invocation -> {
            TransactionCallback<?> action = invocation.getArgument(0);
            return action.doInTransaction(null);
        }).when(transactionTemplate).execute(any());
        service = new TradeOrchestrationService(taskMapper, stepMapper, commandService, publisher,
                transactionTemplate, new ObjectMapper(), enabledProperties(true));
    }

    @Test
    void executeReturnsFalseWhenOrchestrationDisabled() {
        TradeOrchestrationService disabled = new TradeOrchestrationService(taskMapper, stepMapper,
                commandService, publisher, mock(TransactionTemplate.class), new ObjectMapper(),
                enabledProperties(false));

        Assertions.assertFalse(disabled.execute("T1"));
        verify(taskMapper, never()).selectByTaskNo(anyString());
        verify(commandService, never()).execute(any(), any());
    }

    @Test
    void executeSkipsClaimWhenAnotherWorkerOwnsTask() {
        TradeOrchestrationTask task = task("T1");
        when(taskMapper.selectByTaskNo("T1")).thenReturn(task);
        when(taskMapper.tryClaim(eq(1L), anyString(), any(), any())).thenReturn(0);

        Assertions.assertFalse(service.execute("T1"));
        verify(commandService, never()).execute(any(), any());
    }

    @Test
    void executeDoesNotRerunSuccessfulSteps() {
        TradeOrchestrationTask task = task("T1");
        when(taskMapper.selectByTaskNo("T1")).thenReturn(task);
        when(taskMapper.tryClaim(eq(1L), anyString(), any(), any())).thenReturn(1);
        when(stepMapper.selectByTaskNo("T1")).thenReturn(List.of(
                step(1L, 1, "STEP_A", "SUCCESS"),
                step(2L, 2, "STEP_B", "SUCCESS")));

        Assertions.assertTrue(service.execute("T1"));

        verify(commandService, never()).execute(any(), any());
        verify(stepMapper, never()).markRunning(any(), any());
        verify(taskMapper).finish(eq("T1"), eq("SUCCESS"), any(LocalDateTime.class));
    }

    @Test
    void executeRunsPendingStepAndCompletes() {
        TradeOrchestrationTask task = task("T1");
        TradeOrchestrationStep pending = step(1L, 1, "STEP_A", "INIT");
        when(taskMapper.selectByTaskNo("T1")).thenReturn(task);
        when(taskMapper.tryClaim(eq(1L), anyString(), any(), any())).thenReturn(1);
        when(stepMapper.selectByTaskNo("T1")).thenReturn(List.of(pending));
        when(stepMapper.markRunning(eq(1L), any())).thenReturn(1);
        when(commandService.execute(task, pending)).thenReturn("OK");

        Assertions.assertTrue(service.execute("T1"));

        verify(stepMapper).markSuccess(eq(1L), eq("\"OK\""), any(LocalDateTime.class));
        verify(taskMapper).finish(eq("T1"), eq("SUCCESS"), any(LocalDateTime.class));
    }

    @Test
    void stepFailureSchedulesRetry() {
        TradeOrchestrationTask task = task("T1");
        TradeOrchestrationStep pending = step(1L, 1, "STEP_A", "INIT");
        pending.setAttemptCount(0);
        pending.setMaxAttemptCount(5);
        when(taskMapper.selectByTaskNo("T1")).thenReturn(task);
        when(taskMapper.tryClaim(eq(1L), anyString(), any(), any())).thenReturn(1);
        when(stepMapper.selectByTaskNo("T1")).thenReturn(List.of(pending));
        when(stepMapper.markRunning(eq(1L), any())).thenReturn(1);
        when(commandService.execute(task, pending)).thenThrow(new RuntimeException("remote down"));

        Assertions.assertFalse(service.execute("T1"));

        verify(stepMapper).markFailed(eq(1L), eq("FAILED"), eq("remote down"),
                any(LocalDateTime.class), any(LocalDateTime.class));
        verify(taskMapper).markFailed(eq("T1"), eq("FAILED"),
                any(LocalDateTime.class), any(LocalDateTime.class));
        verify(taskMapper, never()).finish(anyString(), anyString(), any());
    }

    @Test
    void stepFailureAtMaxAttemptTransitionsToManualPending() {
        TradeOrchestrationTask task = task("T1");
        TradeOrchestrationStep pending = step(1L, 1, "STEP_A", "FAILED");
        pending.setAttemptCount(4);
        pending.setMaxAttemptCount(5);
        when(taskMapper.selectByTaskNo("T1")).thenReturn(task);
        when(taskMapper.tryClaim(eq(1L), anyString(), any(), any())).thenReturn(1);
        when(stepMapper.selectByTaskNo("T1")).thenReturn(List.of(pending));
        when(stepMapper.markRunning(eq(1L), any())).thenReturn(1);
        when(commandService.execute(task, pending)).thenThrow(new RuntimeException("remote down"));

        Assertions.assertFalse(service.execute("T1"));

        verify(stepMapper).markFailed(eq(1L), eq("MANUAL_PENDING"), eq("remote down"),
                eq(null), any(LocalDateTime.class));
        verify(taskMapper).markFailed(eq("T1"), eq("MANUAL_PENDING"),
                eq(null), any(LocalDateTime.class));
    }

    @Test
    void terminalFailureCompensatesTask() {
        TradeOrchestrationTask task = task("T1");
        TradeOrchestrationStep pending = step(1L, 1, "STEP_A", "INIT");
        when(taskMapper.selectByTaskNo("T1")).thenReturn(task);
        when(taskMapper.tryClaim(eq(1L), anyString(), any(), any())).thenReturn(1);
        when(stepMapper.selectByTaskNo("T1")).thenReturn(List.of(pending));
        when(stepMapper.markRunning(eq(1L), any())).thenReturn(1);
        when(commandService.execute(task, pending))
                .thenThrow(new TradeOrchestrationTerminalException("business terminal"));

        Assertions.assertTrue(service.execute("T1"));

        verify(stepMapper).markFailed(eq(1L), eq("COMPENSATED"), eq("business terminal"),
                eq(null), any(LocalDateTime.class));
        verify(taskMapper).finish(eq("T1"), eq("COMPENSATED"), any(LocalDateTime.class));
        verify(taskMapper, never()).markFailed(anyString(), anyString(), any(), any());
    }

    @Test
    void recoverExecutesRecoverableTasksWhenMessageLost() {
        TradeOrchestrationTask task = task("T1");
        when(taskMapper.selectRecoverable(any(LocalDateTime.class), anyInt())).thenReturn(List.of(task));
        when(taskMapper.selectByTaskNo("T1")).thenReturn(task);
        when(taskMapper.tryClaim(eq(1L), anyString(), any(), any())).thenReturn(1);
        when(stepMapper.selectByTaskNo("T1")).thenReturn(List.of(step(1L, 1, "STEP_A", "SUCCESS")));

        Assertions.assertEquals(1, service.recover());

        verify(taskMapper).selectRecoverable(any(LocalDateTime.class), eq(100));
        verify(taskMapper).finish(eq("T1"), eq("SUCCESS"), any(LocalDateTime.class));
    }

    @Test
    void openStepAlreadyCompleteIsRejectedByCasGuard() {
        TradeOrchestrationTask task = task("T1");
        TradeOrchestrationStep pending = step(1L, 1, "STEP_A", "INIT");
        when(taskMapper.selectByTaskNo("T1")).thenReturn(task);
        when(taskMapper.tryClaim(eq(1L), anyString(), any(), any())).thenReturn(1);
        when(stepMapper.selectByTaskNo("T1")).thenReturn(List.of(pending));
        when(stepMapper.markRunning(eq(1L), any())).thenReturn(0);

        Assertions.assertFalse(service.execute("T1"));

        verify(commandService, never()).execute(any(), any());
        verify(taskMapper).releaseWaiting(eq("T1"), eq("FAILED"), any(), any(LocalDateTime.class));
    }

    private TradeOrchestrationTask task(String taskNo) {
        TradeOrchestrationTask task = new TradeOrchestrationTask();
        task.setId(1L);
        task.setTaskNo(taskNo);
        task.setTaskType("PAY_CONFIRM");
        task.setBizType("ORDER");
        task.setBizNo("TR1");
        task.setStatus("INIT");
        task.setRetryCount(0);
        task.setMaxRetryCount(5);
        task.setContextJson("{}");
        return task;
    }

    private TradeOrchestrationStep step(Long id, int stepNo, String name, String status) {
        TradeOrchestrationStep step = new TradeOrchestrationStep();
        step.setId(id);
        step.setTaskNo("T1");
        step.setStepNo(stepNo);
        step.setStepName(name);
        step.setStepType("REMOTE");
        step.setStatus(status);
        step.setAttemptCount(0);
        step.setMaxAttemptCount(5);
        return step;
    }

    private TradeOrchestrationProperties enabledProperties(boolean enabled) {
        return new TradeOrchestrationProperties(enabled, 100, 60, 5, 30,
                "trade-orchestration-task-topic", 30, 15,
                new BigDecimal("2"), new BigDecimal("0.01"));
    }
}
