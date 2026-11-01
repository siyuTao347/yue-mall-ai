package com.example.moneyagent.application;

import api.agent.money.MoneyErrorCodes;
import api.agent.money.contract.MoneyAdditionalInput;
import api.agent.money.contract.MoneyAgentException;
import com.example.moneyagent.persistence.entity.*;
import com.example.moneyagent.persistence.mapper.*;
import com.example.moneyagent.worker.MoneyTaskWorker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MoneyInputServiceTest {

    private MoneyTaskMapper taskMapper;
    private MoneyCaseMapper caseMapper;
    private MoneyTaskInputMapper taskInputMapper;
    private MoneyInputRequestMapper inputRequestMapper;
    private MoneyRequestMapper requestMapper;
    private MoneyTaskWorker taskWorker;

    private MoneyInputService inputService;

    @BeforeEach
    void setUp() {
        taskMapper = mock(MoneyTaskMapper.class);
        caseMapper = mock(MoneyCaseMapper.class);
        taskInputMapper = mock(MoneyTaskInputMapper.class);
        inputRequestMapper = mock(MoneyInputRequestMapper.class);
        requestMapper = mock(MoneyRequestMapper.class);
        taskWorker = mock(MoneyTaskWorker.class);

        inputService = new MoneyInputService(
                taskMapper, caseMapper, taskInputMapper, inputRequestMapper, requestMapper, taskWorker);
    }

    @Test
    @DisplayName("测试参数校验：空负载或空任务号被拒绝")
    void testParamValidation() {
        assertThrows(MoneyAgentException.class, () ->
                inputService.submitAdditionalInput("mall-agent-service", "REQ-1", null));

        MoneyAdditionalInput emptyTaskInput = MoneyAdditionalInput.builder().taskNo("").build();
        assertThrows(MoneyAgentException.class, () ->
                inputService.submitAdditionalInput("mall-agent-service", "REQ-1", emptyTaskInput));
    }

    @Test
    @DisplayName("测试持久幂等重放：相同 requestId 直接返回 true")
    void testIdempotentReplay() {
        MoneyRequestEntity req = MoneyRequestEntity.builder()
                .callerId("mall-agent-service")
                .requestId("REQ-REPLAY-1")
                .action("PROVIDE_INPUT")
                .state("COMPLETED")
                .build();
        when(requestMapper.selectOne(any())).thenReturn(req);

        MoneyAdditionalInput input = MoneyAdditionalInput.builder().taskNo("MT001").build();
        boolean res = inputService.submitAdditionalInput("mall-agent-service", "REQ-REPLAY-1", input);

        assertTrue(res);
        verify(taskMapper, never()).selectOne(any());
    }

    @Test
    @DisplayName("测试终态任务不可补充材料")
    void testTerminalTaskCannotProvideInput() {
        when(requestMapper.selectOne(any())).thenReturn(null);
        MoneyTaskEntity terminalTask = MoneyTaskEntity.builder()
                .taskNo("MT001")
                .state("COMPLETED")
                .build();
        when(taskMapper.selectOne(any())).thenReturn(terminalTask);

        MoneyAdditionalInput input = MoneyAdditionalInput.builder().taskNo("MT001").build();
        MoneyAgentException ex = assertThrows(MoneyAgentException.class, () ->
                inputService.submitAdditionalInput("mall-agent-service", "REQ-2", input));

        assertEquals(MoneyErrorCodes.MONEY_FORBIDDEN, ex.getCode());
    }

    @Test
    @DisplayName("测试输入版本冲突被拒绝")
    void testInputVersionConflict() {
        when(requestMapper.selectOne(any())).thenReturn(null);
        MoneyTaskEntity task = MoneyTaskEntity.builder()
                .taskNo("MT001")
                .state("INPUT_REQUIRED")
                .version(1L)
                .build();
        when(taskMapper.selectOne(any())).thenReturn(task);

        MoneyInputRequestEntity inputReq = MoneyInputRequestEntity.builder()
                .inputNo("INP001")
                .inputVersion(2)
                .state("PENDING")
                .build();
        when(inputRequestMapper.selectOne(any())).thenReturn(inputReq);

        MoneyAdditionalInput input = MoneyAdditionalInput.builder()
                .taskNo("MT001")
                .inputRequestNo("INP001")
                .expectedInputVersion(1L) // 冲突：期望 1，实际 2
                .answers(Map.of("proofImage", "https://img.example.com/slip.png"))
                .build();

        MoneyAgentException ex = assertThrows(MoneyAgentException.class, () ->
                inputService.submitAdditionalInput("mall-agent-service", "REQ-3", input));

        assertEquals(MoneyErrorCodes.MONEY_VERSION_CONFLICT, ex.getCode());
    }

    @Test
    @DisplayName("测试成功补证：持久化材料、恢复 Task 为 SUBMITTED、恢复 Case 为 OPEN、递增 fencingToken 并触发 Worker")
    void testSubmitAdditionalInputSuccess() {
        when(requestMapper.selectOne(any())).thenReturn(null);
        MoneyTaskEntity task = MoneyTaskEntity.builder()
                .taskNo("MT001")
                .caseNo("MC001")
                .state("INPUT_REQUIRED")
                .waitReason("USER_INPUT_REQUIRED")
                .version(1L)
                .fencingToken(1L)
                .build();
        when(taskMapper.selectOne(any())).thenReturn(task);

        MoneyInputRequestEntity inputReq = MoneyInputRequestEntity.builder()
                .inputNo("INP001")
                .inputVersion(1)
                .state("PENDING")
                .build();
        when(inputRequestMapper.selectOne(any())).thenReturn(inputReq);

        MoneyCaseEntity caseEntity = MoneyCaseEntity.builder()
                .caseNo("MC001")
                .state("NEED_INPUT")
                .build();
        when(caseMapper.selectOne(any())).thenReturn(caseEntity);

        MoneyAdditionalInput input = MoneyAdditionalInput.builder()
                .taskNo("MT001")
                .inputRequestNo("INP001")
                .expectedInputVersion(1L)
                .answers(Map.of("bankSlip", "slip_12345.jpg"))
                .build();

        boolean success = inputService.submitAdditionalInput("mall-agent-service", "REQ-SUCCESS-1", input);
        assertTrue(success);

        // 验证材料写入
        verify(taskInputMapper, times(1)).insert(any(MoneyTaskInputEntity.class));
        // 验证 inputRequest 更新为 ANSWERED
        assertEquals("ANSWERED", inputReq.getState());
        verify(inputRequestMapper, times(1)).updateById(inputReq);
        // 验证 task 状态恢复为 SUBMITTED，版本与 fencingToken 递增
        assertEquals("SUBMITTED", task.getState());
        assertNull(task.getWaitReason());
        assertEquals(2L, task.getVersion());
        assertEquals(2L, task.getFencingToken());
        verify(taskMapper, times(1)).updateById(task);
        // 验证 case 状态由 NEED_INPUT 恢复为 OPEN
        assertEquals("OPEN", caseEntity.getState());
        verify(caseMapper, times(1)).updateById(caseEntity);
        // 验证请求幂等记录写入
        verify(requestMapper, times(1)).insert(any(MoneyRequestEntity.class));
        // 验证触发 Worker 执行
        verify(taskWorker, times(1)).runOneTask();
    }
}

