package com.example.agent.customer.money;

import api.agent.money.contract.MoneyCaseReference;
import api.agent.money.contract.MoneyCaseView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CustomerMoneyTaskSyncWorkerTest {

    private AgentMoneyBindingMapper bindingMapper;
    private MoneyAgentClient moneyAgentClient;
    private CustomerMoneyDeliveryService deliveryService;
    private CustomerMoneyAnswerAssembler answerAssembler;
    private CustomerMoneyTaskSyncWorker syncWorker;

    @BeforeEach
    void setUp() {
        bindingMapper = mock(AgentMoneyBindingMapper.class);
        moneyAgentClient = mock(MoneyAgentClient.class);
        deliveryService = mock(CustomerMoneyDeliveryService.class);
        answerAssembler = new CustomerMoneyAnswerAssembler();
        syncWorker = new CustomerMoneyTaskSyncWorker(bindingMapper, moneyAgentClient, deliveryService, answerAssembler);
    }

    @Test
    @DisplayName("测试轮询饥饿消除：当活动案件超过20个时，轮询时间推进并多批次轮转调度后续案件")
    void testNoStarvationWhenActiveCasesExceed20() {
        List<AgentMoneyBindingEntity> firstBatch = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            firstBatch.add(AgentMoneyBindingEntity.builder()
                    .id((long) i)
                    .caseNo("MC_TEST_" + i)
                    .orderNo("ORD_" + i)
                    .userId(1000L + i)
                    .subscriptionState("ACTIVE")
                    .caseState("NEED_REVIEW")
                    .taskState("COMPLETED")
                    .projectionRevision(1L)
                    .build());
        }

        List<AgentMoneyBindingEntity> secondBatch = new ArrayList<>();
        for (int i = 21; i <= 25; i++) {
            secondBatch.add(AgentMoneyBindingEntity.builder()
                    .id((long) i)
                    .caseNo("MC_TEST_" + i)
                    .orderNo("ORD_" + i)
                    .userId(1000L + i)
                    .subscriptionState("ACTIVE")
                    .caseState("OPEN")
                    .taskState("WORKING")
                    .projectionRevision(1L)
                    .build());
        }

        // 模拟第1轮返回满20条，第2轮返回剩余5条，第3轮返回空
        when(bindingMapper.selectList(any())).thenReturn(firstBatch, secondBatch, List.of());

        // 模拟远端案件查询：案件处于相同状态，无修订变更
        when(moneyAgentClient.getCase(any(MoneyCaseReference.class), any())).thenAnswer(invocation -> {
            MoneyCaseReference ref = invocation.getArgument(0);
            return MoneyCaseView.builder()
                    .caseNo(ref.getCaseNo())
                    .caseState("NEED_REVIEW")
                    .taskState("COMPLETED")
                    .projectionRevision(1L)
                    .build();
        });

        // 触发调度
        syncWorker.syncActiveCases();

        // 验证两批共 25 个 binding 都被同步并更新了 nextPollAt
        verify(bindingMapper, times(25)).updateById(any(AgentMoneyBindingEntity.class));

        // 验证前20个案件的 nextPollAt 都已推进到未来 (NEED_REVIEW: +60s)
        for (AgentMoneyBindingEntity b : firstBatch) {
            assertNotNull(b.getNextPollAt(), "批次1案件的 nextPollAt 必须被设置以防死锁");
            assertTrue(b.getNextPollAt().isAfter(LocalDateTime.now(ZoneOffset.UTC).plusSeconds(40)));
        }

        // 验证排在20名以后的案件 21~25 也被成功轮询到，彻底解决轮询饥饿
        for (AgentMoneyBindingEntity b : secondBatch) {
            assertNotNull(b.getNextPollAt(), "第2批案件必须被正常调度处理");
        }
    }

    @Test
    @DisplayName("测试无版本/状态变化时，仍推进 next_poll_at，防止同批长耗时案件死锁霸占 LIMIT 20")
    void testSyncOneBindingAdvancesNextPollAtEvenWhenNoStateChange() {
        LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC);
        AgentMoneyBindingEntity binding = AgentMoneyBindingEntity.builder()
                .id(1L)
                .caseNo("MC_STATIC_01")
                .orderNo("ORD_STATIC_01")
                .userId(100L)
                .subscriptionState("ACTIVE")
                .caseState("NEED_REVIEW")
                .taskState("COMPLETED")
                .projectionRevision(2L)
                .build();

        when(moneyAgentClient.getCase(any(MoneyCaseReference.class), any())).thenReturn(
                MoneyCaseView.builder()
                        .caseNo("MC_STATIC_01")
                        .caseState("NEED_REVIEW")
                        .taskState("COMPLETED")
                        .projectionRevision(2L)
                        .build()
        );

        boolean updatedForDelivery = syncWorker.syncOneBinding(binding);
        // 无内容变更，不触发投递
        assertFalse(updatedForDelivery);

        // 但 DB 必须更新 nextPollAt
        verify(bindingMapper, times(1)).updateById(binding);
        assertNotNull(binding.getNextPollAt());
        // NEED_REVIEW 状态下低频轮询 (+60s)
        assertTrue(binding.getNextPollAt().isAfter(nowUtc.plusSeconds(50)));
    }

    @Test
    @DisplayName("测试不同案件状态的自适应轮询周期：NEED_INPUT(+30s), NEED_REVIEW(+60s), OPEN(+10s), 终态(null)")
    void testStateBasedPollingIntervals() {
        LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC);

        // 1. NEED_INPUT: +30s
        AgentMoneyBindingEntity bindingInput = AgentMoneyBindingEntity.builder()
                .id(1L).caseNo("MC_1").orderNo("O_1").userId(100L).subscriptionState("ACTIVE")
                .caseState("OPEN").projectionRevision(1L).build();
        when(moneyAgentClient.getCase(any(MoneyCaseReference.class), any())).thenReturn(
                MoneyCaseView.builder().caseNo("MC_1").caseState("NEED_INPUT").projectionRevision(2L).build()
        );
        syncWorker.syncOneBinding(bindingInput);
        assertNotNull(bindingInput.getNextPollAt());
        assertTrue(bindingInput.getNextPollAt().isAfter(nowUtc.plusSeconds(25)));
        assertTrue(bindingInput.getNextPollAt().isBefore(nowUtc.plusSeconds(35)));

        // 2. RESOLVED 终态: null
        AgentMoneyBindingEntity bindingResolved = AgentMoneyBindingEntity.builder()
                .id(2L).caseNo("MC_2").orderNo("O_2").userId(100L).subscriptionState("ACTIVE")
                .caseState("OPEN").projectionRevision(1L).build();
        when(moneyAgentClient.getCase(any(MoneyCaseReference.class), any())).thenReturn(
                MoneyCaseView.builder().caseNo("MC_2").caseState("RESOLVED").projectionRevision(2L).build()
        );
        syncWorker.syncOneBinding(bindingResolved);
        assertNull(bindingResolved.getNextPollAt(), "终态案件应停止轮询 (nextPollAt = null)");
    }

    @Test
    @DisplayName("测试 RPC 异常时执行退避并推进 next_poll_at，防止错误死循环霸占队列")
    void testSyncOneBindingBackoffOnRpcError() {
        LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC);
        AgentMoneyBindingEntity binding = AgentMoneyBindingEntity.builder()
                .id(99L)
                .caseNo("MC_ERR_01")
                .orderNo("ORD_ERR_01")
                .userId(100L)
                .subscriptionState("ACTIVE")
                .caseState("OPEN")
                .build();

        when(moneyAgentClient.getCase(any(MoneyCaseReference.class), any()))
                .thenThrow(new RuntimeException("Money Agent RPC Timeout"));

        assertThrows(RuntimeException.class, () -> syncWorker.syncOneBinding(binding));

        // 验证更新了 nextPollAt 为当前时间 + 15 秒退避
        verify(bindingMapper, times(1)).updateById(binding);
        assertNotNull(binding.getNextPollAt());
        assertTrue(binding.getNextPollAt().isAfter(nowUtc.plusSeconds(10)));
        assertTrue(binding.getNextPollAt().isBefore(nowUtc.plusSeconds(20)));
    }
}

