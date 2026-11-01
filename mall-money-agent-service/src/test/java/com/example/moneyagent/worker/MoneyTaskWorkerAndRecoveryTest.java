package com.example.moneyagent.worker;

import com.example.moneyagent.investigation.MoneyInvestigationGraph;
import com.example.moneyagent.persistence.entity.MoneyCaseEntity;
import com.example.moneyagent.persistence.entity.MoneyTaskEntity;
import com.example.moneyagent.persistence.mapper.MoneyCaseMapper;
import com.example.moneyagent.persistence.mapper.MoneyTaskMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MoneyTaskWorkerAndRecoveryTest {

    private MoneyTaskMapper taskMapper;
    private MoneyCaseMapper caseMapper;
    private MoneyInvestigationGraph investigationGraph;

    private MoneyTaskWorker taskWorker;
    private MoneyTaskRecoveryJob recoveryJob;

    @BeforeEach
    void setUp() {
        taskMapper = mock(MoneyTaskMapper.class);
        caseMapper = mock(MoneyCaseMapper.class);
        investigationGraph = mock(MoneyInvestigationGraph.class);

        taskWorker = new MoneyTaskWorker(taskMapper, caseMapper, investigationGraph);
        recoveryJob = new MoneyTaskRecoveryJob(taskMapper);
    }

    @Test
    @DisplayName("测试 Worker 认领任务：加租约锁(30秒)、状态切为 WORKING、递增版本与 fencingToken")
    void testClaimTask() {
        when(taskMapper.selectRunnableTaskForUpdate(any())).thenReturn(null);
        assertNull(taskWorker.claimTask());

        MoneyTaskEntity runnableTask = MoneyTaskEntity.builder()
                .taskNo("MT_RUN_01")
                .caseNo("MC_01")
                .state("SUBMITTED")
                .version(1L)
                .fencingToken(1L)
                .build();
        when(taskMapper.selectRunnableTaskForUpdate(any())).thenReturn(runnableTask);

        MoneyTaskEntity claimed = taskWorker.claimTask();
        assertNotNull(claimed);
        assertEquals("WORKING", claimed.getState());
        assertNotNull(claimed.getLeaseOwner());
        assertTrue(claimed.getLeaseOwner().startsWith("WORKER_"));
        assertNotNull(claimed.getLeaseUntil());
        assertEquals(2L, claimed.getVersion());
        assertEquals(2L, claimed.getFencingToken());

        verify(taskMapper, times(1)).updateById(claimed);
    }

    @Test
    @DisplayName("测试 Worker 执行任务并最终释放租约")
    void testRunOneTaskReleasesLease() {
        MoneyTaskEntity task = MoneyTaskEntity.builder()
                .taskNo("MT_RUN_02")
                .caseNo("MC_02")
                .state("SUBMITTED")
                .version(1L)
                .fencingToken(1L)
                .build();
        when(taskMapper.selectRunnableTaskForUpdate(any())).thenReturn(task);

        MoneyCaseEntity caseEntity = MoneyCaseEntity.builder()
                .caseNo("MC_02")
                .state("OPEN")
                .build();
        when(caseMapper.selectOne(any())).thenReturn(caseEntity);

        boolean executed = taskWorker.runOneTask();
        assertTrue(executed);

        verify(investigationGraph, times(1)).executeInvestigation(eq(caseEntity), eq(task));
        // 最终释放租约
        assertNull(task.getLeaseOwner());
        assertNull(task.getLeaseUntil());
        verify(taskMapper, atLeast(2)).updateById(task);
    }

    @Test
    @DisplayName("测试租约超时恢复 Job：发现超时任务后清除租约、重置为 SUBMITTED 并递增版本")
    void testRecoverExpiredLeaseTasks() {
        LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC);
        MoneyTaskEntity expiredTask = MoneyTaskEntity.builder()
                .taskNo("MT_EXPIRED_01")
                .caseNo("MC_01")
                .state("WORKING")
                .leaseOwner("WORKER_DEAD_99")
                .leaseUntil(nowUtc.minusSeconds(10)) // 已过期 10 秒
                .version(3L)
                .build();

        when(taskMapper.selectList(any())).thenReturn(List.of(expiredTask));

        recoveryJob.recoverExpiredLeaseTasks();

        assertNull(expiredTask.getLeaseOwner());
        assertNull(expiredTask.getLeaseUntil());
        assertEquals("SUBMITTED", expiredTask.getState());
        assertNotNull(expiredTask.getNextRunAt());
        assertEquals(4L, expiredTask.getVersion());

        verify(taskMapper, times(1)).updateById(expiredTask);
    }
}

