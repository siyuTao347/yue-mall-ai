package com.example.agent.customer.money;

import api.agent.AgentActor;
import api.agent.money.contract.MoneyTaskReceipt;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CustomerMoneyControllerTest {

    private CustomerMoneyBridgeService bridgeService;
    private AgentMoneyBindingMapper bindingMapper;
    private AgentMoneyDispatchMapper dispatchMapper;
    private AgentMoneyStaffNoteMapper staffNoteMapper;
    private MoneyAgentClient moneyAgentClient;
    private CustomerMoneyNoticeService noticeService;
    private CustomerMoneyController controller;

    @BeforeEach
    void setUp() {
        bridgeService = mock(CustomerMoneyBridgeService.class);
        bindingMapper = mock(AgentMoneyBindingMapper.class);
        dispatchMapper = mock(AgentMoneyDispatchMapper.class);
        staffNoteMapper = mock(AgentMoneyStaffNoteMapper.class);
        moneyAgentClient = mock(MoneyAgentClient.class);
        noticeService = mock(CustomerMoneyNoticeService.class);
        controller = new CustomerMoneyController(bridgeService, bindingMapper, dispatchMapper, staffNoteMapper, moneyAgentClient, noticeService);
    }

    @Test
    void registerInvestigation_callsBridgeService() {
        when(bridgeService.handleMoneyIntent(eq("PAYMENT_ANOMALY"), any(), eq(100L), any()))
                .thenReturn(new CustomerMoneyBridgeService.MoneyBridgeResponse("已受理", "MC_01", "MT_01"));

        AgentActor actor = new AgentActor(100L, "USER");
        var resp = controller.registerInvestigation(actor, Map.of("orderNo", "T1001", "sessionNo", "S1"));
        assertNotNull(resp);
        verify(bridgeService).handleMoneyIntent(eq("PAYMENT_ANOMALY"), eq("S1"), eq(100L), any());
    }

    @Test
    void getDispatch_validOwner_returnsDispatch() {
        AgentMoneyDispatchEntity entity = AgentMoneyDispatchEntity.builder()
                .dispatchNo("MD_01")
                .userId(100L)
                .state("ACCEPTED")
                .build();
        when(dispatchMapper.selectOne(any())).thenReturn(entity);

        AgentActor actor = new AgentActor(100L, "USER");
        var resp = controller.getDispatch(actor, "MD_01");
        assertNotNull(resp);
    }

    @Test
    void getDispatch_otherUser_throwsForbidden() {
        AgentMoneyDispatchEntity entity = AgentMoneyDispatchEntity.builder()
                .dispatchNo("MD_01")
                .userId(200L)
                .state("ACCEPTED")
                .build();
        when(dispatchMapper.selectOne(any())).thenReturn(entity);

        AgentActor actor = new AgentActor(100L, "USER");
        assertThrows(RuntimeException.class, () -> controller.getDispatch(actor, "MD_01"));
    }

    @Test
    void cancelCase_updatesSubscriptionToMuted() {
        AgentMoneyBindingEntity binding = AgentMoneyBindingEntity.builder()
                .caseNo("MC_01")
                .taskNo("MT_01")
                .orderNo("T1001")
                .userId(100L)
                .grantRef("GRANT_01")
                .subscriptionState("ACTIVE")
                .build();
        when(bindingMapper.selectOne(any())).thenReturn(binding);

        AgentActor actor = new AgentActor(100L, "USER");
        var resp = controller.cancelCase(actor, "MC_01", Map.of("reason", "用户取消"));
        assertNotNull(resp);
        assertEquals("MUTED", binding.getSubscriptionState());
        verify(bindingMapper).updateById(binding);
    }

    @Test
    void getStaffSessionMoneyCases_staffRole_returnsCasesAndNotes() {
        AgentActor staff = new AgentActor(999L, "STAFF");
        when(bindingMapper.selectList(any())).thenReturn(List.of());
        when(staffNoteMapper.selectList(any())).thenReturn(List.of());

        var resp = controller.getStaffSessionMoneyCases(staff, "S1");
        assertNotNull(resp);
    }

    @Test
    void getStaffSessionMoneyCases_userRole_throwsForbidden() {
        AgentActor user = new AgentActor(100L, "USER");
        assertThrows(RuntimeException.class, () -> controller.getStaffSessionMoneyCases(user, "S1"));
    }

    @Test
    void receiveNotice_callsNoticeService() {
        when(noticeService.recordAndProcessNotice(eq("MONEY_AGENT"), any(), any())).thenReturn(true);
        var resp = controller.receiveNotice(Map.of("caseNo", "MC_01", "orderNo", "T1001"));
        assertNotNull(resp);
        verify(noticeService).recordAndProcessNotice(eq("MONEY_AGENT"), any(), any());
    }
}

