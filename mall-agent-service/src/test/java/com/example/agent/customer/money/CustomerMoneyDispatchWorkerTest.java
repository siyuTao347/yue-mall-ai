package com.example.agent.customer.money;

import api.agent.money.contract.MoneyTaskReceipt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CustomerMoneyDispatchWorkerTest {

    private AgentMoneyDispatchMapper dispatchMapper;
    private AgentMoneyBindingMapper bindingMapper;
    private MoneyAgentClient moneyAgentClient;
    private CustomerMoneyDispatchWorker worker;

    @BeforeEach
    void setUp() {
        dispatchMapper = mock(AgentMoneyDispatchMapper.class);
        bindingMapper = mock(AgentMoneyBindingMapper.class);
        moneyAgentClient = mock(MoneyAgentClient.class);
        worker = new CustomerMoneyDispatchWorker(dispatchMapper, bindingMapper, moneyAgentClient);
    }

    @Test
    void dispatchOne_success_updatesDispatchAndInsertsBinding() {
        AgentMoneyDispatchEntity dispatch = AgentMoneyDispatchEntity.builder()
                .dispatchNo("MD_001")
                .userId(100L)
                .sessionNo("S1")
                .requestId("REQ_001")
                .payloadJson("{\"orderNo\":\"T1001\",\"problemFamily\":\"PAYMENT_CONFIRMATION\"}")
                .grantRef("GRANT_01")
                .state("PENDING")
                .version(1L)
                .build();

        MoneyTaskReceipt receipt = MoneyTaskReceipt.builder()
                .caseNo("MC_SUCCESS_01")
                .taskNo("MT_SUCCESS_01")
                .contextId("CTX_01")
                .projectionRevision(1L)
                .build();
        when(moneyAgentClient.start(any())).thenReturn(receipt);
        when(bindingMapper.selectOne(any())).thenReturn(null);

        boolean ok = worker.dispatchOne(dispatch);
        assertTrue(ok);

        assertEquals("ACCEPTED", dispatch.getState());
        assertEquals("MT_SUCCESS_01", dispatch.getRemoteTaskNo());
        verify(dispatchMapper, atLeastOnce()).updateById(dispatch);

        ArgumentCaptor<AgentMoneyBindingEntity> bindingCaptor = ArgumentCaptor.forClass(AgentMoneyBindingEntity.class);
        verify(bindingMapper).insert(bindingCaptor.capture());
        assertEquals("MC_SUCCESS_01", bindingCaptor.getValue().getCaseNo());
        assertEquals("T1001", bindingCaptor.getValue().getOrderNo());
    }

    @Test
    void dispatchOne_retryableFailure_setsNextAttemptWithBackoff() {
        AgentMoneyDispatchEntity dispatch = AgentMoneyDispatchEntity.builder()
                .dispatchNo("MD_002")
                .userId(100L)
                .sessionNo("S1")
                .requestId("REQ_002")
                .payloadJson("{\"orderNo\":\"T1001\",\"problemFamily\":\"PAYMENT_CONFIRMATION\"}")
                .grantRef("GRANT_01")
                .state("PENDING")
                .attempts(1)
                .version(1L)
                .build();

        when(moneyAgentClient.start(any())).thenThrow(new RuntimeException("Network timeout"));

        boolean ok = worker.dispatchOne(dispatch);
        assertFalse(ok);

        assertEquals("RETRY_WAIT", dispatch.getState());
        assertEquals(2, dispatch.getAttempts());
        assertNotNull(dispatch.getNextAttemptAt());
        verify(dispatchMapper, atLeastOnce()).updateById(dispatch);
        verify(bindingMapper, never()).insert(any(AgentMoneyBindingEntity.class));
    }

    @Test
    void processPendingDispatches_recoversExpiredSendingDispatches() {
        AgentMoneyDispatchEntity expiredSending = AgentMoneyDispatchEntity.builder()
                .dispatchNo("MD_EXPIRED_01")
                .userId(100L)
                .sessionNo("S1")
                .state("SENDING")
                .leaseOwner("WORKER_CRASHED")
                .leaseUntil(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1))
                .attempts(1)
                .build();

        // 第一次查询返回超时的 SENDING 任务，第二次查询普通 pending 任务为空
        when(dispatchMapper.selectList(any()))
                .thenReturn(java.util.List.of(expiredSending))
                .thenReturn(java.util.List.of());

        worker.processPendingDispatches();

        assertEquals("RETRY_WAIT", expiredSending.getState());
        assertNull(expiredSending.getLeaseOwner());
        assertNull(expiredSending.getLeaseUntil());
        assertEquals("LEASE_EXPIRED_DURING_SENDING", expiredSending.getLastError());
        assertEquals(2, expiredSending.getAttempts());
        assertNotNull(expiredSending.getNextAttemptAt());
        verify(dispatchMapper, atLeastOnce()).updateById(expiredSending);
    }
}
