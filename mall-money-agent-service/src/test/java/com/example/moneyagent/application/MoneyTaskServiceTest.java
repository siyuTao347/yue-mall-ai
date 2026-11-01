package com.example.moneyagent.application;

import api.agent.money.MoneyErrorCodes;
import api.agent.money.contract.MoneyAgentException;
import api.agent.money.contract.MoneyCancelReceipt;
import api.agent.money.contract.MoneyCancelRequest;
import api.agent.money.contract.MoneyTaskView;
import com.example.moneyagent.persistence.entity.MoneyCaseEntity;
import com.example.moneyagent.persistence.entity.MoneyRequestEntity;
import com.example.moneyagent.persistence.entity.MoneyTaskAccessEntity;
import com.example.moneyagent.persistence.entity.MoneyTaskEntity;
import com.example.moneyagent.persistence.mapper.MoneyCaseMapper;
import com.example.moneyagent.persistence.mapper.MoneyRequestMapper;
import com.example.moneyagent.persistence.mapper.MoneyTaskAccessMapper;
import com.example.moneyagent.persistence.mapper.MoneyTaskMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MoneyTaskServiceTest {

    private MoneyTaskMapper taskMapper;
    private MoneyCaseMapper caseMapper;
    private MoneyTaskAccessMapper taskAccessMapper;
    private MoneyRequestMapper requestMapper;

    private MoneyTaskService taskService;

    @BeforeEach
    void setUp() {
        taskMapper = mock(MoneyTaskMapper.class);
        caseMapper = mock(MoneyCaseMapper.class);
        taskAccessMapper = mock(MoneyTaskAccessMapper.class);
        requestMapper = mock(MoneyRequestMapper.class);

        taskService = new MoneyTaskService(taskMapper, caseMapper, taskAccessMapper, requestMapper);
    }

    @Test
    @DisplayName("测试任务查询视图：空入参或不存在任务返回 null")
    void testGetTaskView() {
        assertNull(taskService.getTaskView(null));
        assertNull(taskService.getTaskView("  "));

        when(taskMapper.selectOne(any())).thenReturn(null);
        assertNull(taskService.getTaskView("MT_NON_EXISTENT"));

        MoneyTaskEntity task = MoneyTaskEntity.builder()
                .taskNo("MT_001")
                .caseNo("MC_001")
                .contextId("CTX_001")
                .state("WORKING")
                .version(2L)
                .build();
        when(taskMapper.selectOne(any())).thenReturn(task);

        MoneyTaskView view = taskService.getTaskView("MT_001");
        assertNotNull(view);
        assertEquals("MT_001", view.getTaskNo());
        assertEquals("MC_001", view.getCaseNo());
        assertEquals("WORKING", view.getTaskState());
        assertEquals(2L, view.getTaskVersion());
    }

    @Test
    @DisplayName("测试取消任务参数校验与存在性校验")
    void testCancelParamAndNotFound() {
        assertThrows(MoneyAgentException.class, () ->
                taskService.cancelTask("mall-agent-service", "REQ-1", null));

        when(taskMapper.selectOne(any())).thenReturn(null);
        MoneyCancelRequest req = MoneyCancelRequest.builder().taskNo("MT_NOT_FOUND").build();
        MoneyAgentException ex = assertThrows(MoneyAgentException.class, () ->
                taskService.cancelTask("mall-agent-service", "REQ-1", req));
        assertEquals(MoneyErrorCodes.MONEY_TASK_NOT_FOUND, ex.getCode());
    }

    @Test
    @DisplayName("测试已处于终态的任务取消：幂等直接返回当前状态")
    void testCancelAlreadyTerminalTask() {
        MoneyTaskEntity completedTask = MoneyTaskEntity.builder()
                .taskNo("MT_001")
                .state("COMPLETED")
                .build();
        when(taskMapper.selectOne(any())).thenReturn(completedTask);

        MoneyCancelRequest req = MoneyCancelRequest.builder().taskNo("MT_001").build();
        MoneyCancelReceipt receipt = taskService.cancelTask("mall-agent-service", "REQ-2", req);

        assertTrue(receipt.isCanceled());
        assertEquals("COMPLETED", receipt.getTaskState());
        verify(taskMapper, never()).updateById(any(MoneyTaskEntity.class));
    }

    @Test
    @DisplayName("测试取消版本冲突被拒绝")
    void testCancelVersionConflict() {
        MoneyTaskEntity task = MoneyTaskEntity.builder()
                .taskNo("MT_001")
                .state("WORKING")
                .version(2L)
                .build();
        when(taskMapper.selectOne(any())).thenReturn(task);

        MoneyCancelRequest req = MoneyCancelRequest.builder()
                .taskNo("MT_001")
                .expectedTaskVersion(1L) // 期望 1，实际 2
                .build();

        MoneyAgentException ex = assertThrows(MoneyAgentException.class, () ->
                taskService.cancelTask("mall-agent-service", "REQ-3", req));
        assertEquals(MoneyErrorCodes.MONEY_VERSION_CONFLICT, ex.getCode());
    }

    @Test
    @DisplayName("测试多方订阅下的取消：仅静音当前客户端订阅，不取消任务且不关闭案件")
    void testCancelWithMultipleSubscribersMutesCaller() {
        MoneyTaskEntity task = MoneyTaskEntity.builder()
                .taskNo("MT_SHARED")
                .caseNo("MC_SHARED")
                .state("WORKING")
                .version(1L)
                .build();
        when(taskMapper.selectOne(any())).thenReturn(task);

        MoneyTaskAccessEntity acc1 = MoneyTaskAccessEntity.builder()
                .taskNo("MT_SHARED")
                .callerId("mall-agent-service")
                .audienceScope("BUYER:100")
                .build();
        MoneyTaskAccessEntity acc2 = MoneyTaskAccessEntity.builder()
                .taskNo("MT_SHARED")
                .callerId("finance-ops-portal")
                .audienceScope("FINANCE:999")
                .build();
        when(taskAccessMapper.selectList(any())).thenReturn(List.of(acc1, acc2));

        MoneyCancelRequest req = MoneyCancelRequest.builder()
                .taskNo("MT_SHARED")
                .reason("用户关闭客服窗口")
                .build();

        MoneyCancelReceipt receipt = taskService.cancelTask("mall-agent-service", "REQ-4", req);

        assertTrue(receipt.isCanceled());
        assertEquals("WORKING", receipt.getTaskState());
        assertTrue(receipt.getMessage().contains("静音"));
        assertEquals("MUTED", acc1.getAudienceScope());
        verify(taskAccessMapper, times(1)).updateById(acc1);

        // 任务状态没有被改成 CANCELED，案件没有被关闭
        verify(caseMapper, never()).updateById(any(MoneyCaseEntity.class));
        assertEquals("WORKING", task.getState());
    }

    @Test
    @DisplayName("测试唯一定阅方取消：将 Task 独立取消 (CANCELED)，但不关闭案件")
    void testCancelSoleSubscriberCancelsTaskOnly() {
        MoneyTaskEntity task = MoneyTaskEntity.builder()
                .taskNo("MT_SOLE")
                .caseNo("MC_SOLE")
                .state("WORKING")
                .version(1L)
                .build();
        when(taskMapper.selectOne(any())).thenReturn(task);

        MoneyTaskAccessEntity acc = MoneyTaskAccessEntity.builder()
                .taskNo("MT_SOLE")
                .callerId("mall-agent-service")
                .build();
        when(taskAccessMapper.selectList(any())).thenReturn(List.of(acc));

        MoneyCancelRequest req = MoneyCancelRequest.builder()
                .taskNo("MT_SOLE")
                .reason("用户主动撤回")
                .build();

        MoneyCancelReceipt receipt = taskService.cancelTask("mall-agent-service", "REQ-5", req);

        assertTrue(receipt.isCanceled());
        assertEquals("CANCELED", receipt.getTaskState());
        assertEquals("CANCELED", task.getState());
        assertEquals("用户主动撤回", task.getLastErrorCode());
        assertEquals(2L, task.getVersion());

        verify(taskMapper, times(1)).updateById(task);
        // 关键点：取消 Task 不应该关闭 Case
        verify(caseMapper, never()).updateById(any(MoneyCaseEntity.class));
    }
}
