package com.example.item.service;

import api.agent.AgentActor;
import api.exception.ForbiddenException;
import com.example.item.mapper.*;
import com.example.item.entity.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AgentOrderQueryServiceTest {
    @Test void ownerChecksDoNotGrantAdminBypass(){assertTrue(AgentOrderQueryService.owns(new AgentActor(1,"USER"),1L,2L));assertTrue(AgentOrderQueryService.owns(new AgentActor(2,"USER"),1L,2L));assertFalse(AgentOrderQueryService.owns(new AgentActor(3,"ADMIN"),1L,2L));}
    @Test void absentAndForeignOrdersReturnSameForbiddenResult(){var orders=mock(TradeOrderMapper.class);var service=new AgentOrderQueryService(orders,mock(DisputeMapper.class),mock(TradeOrderStatusLogMapper.class),mock(DeliveryEvidenceMapper.class));var actor=new AgentActor(9,"ADMIN");assertThrows(ForbiddenException.class,()->service.queryOrder(actor,"TR123456"));var order=new TradeOrder();order.setBuyerId(1L);order.setSellerId(2L);when(orders.selectOne(any())).thenReturn(order);assertThrows(ForbiddenException.class,()->service.queryOrder(actor,"TR123456"));}
    @Test void deliveryEvidenceQueryChecksOwnershipBeforeReadingContent(){var evidence=mock(DeliveryEvidenceMapper.class);var service=new AgentOrderQueryService(mock(TradeOrderMapper.class),mock(DisputeMapper.class),mock(TradeOrderStatusLogMapper.class),evidence);assertThrows(ForbiddenException.class,()->service.queryDeliveryEvidenceSummary(new AgentActor(9,"USER"),"TR123456"));verifyNoInteractions(evidence);}
}
