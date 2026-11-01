package com.example.agent.customer.money;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CustomerMoneyContextLoaderTest {

    private AgentMoneyBindingMapper bindingMapper;
    private CustomerMoneyContextLoader contextLoader;

    @BeforeEach
    void setUp() {
        bindingMapper = mock(AgentMoneyBindingMapper.class);
        contextLoader = new CustomerMoneyContextLoader(bindingMapper);
    }

    @Test
    void loadContext_populatesMoneyCasesIntoEntityRegistry() {
        AgentMoneyBindingEntity binding = AgentMoneyBindingEntity.builder()
                .caseNo("MC_12345678901234567890123456789012")
                .orderNo("T202610310001")
                .caseState("OPEN")
                .taskState("SUBMITTED")
                .projectionRevision(1L)
                .build();
        when(bindingMapper.selectList(any())).thenReturn(List.of(binding));

        ObjectNode context = JsonNodeFactory.instance.objectNode();
        context.put("turnNo", 2);

        contextLoader.loadContext(1001L, context);

        assertTrue(context.has("entityRegistry"));
        assertTrue(context.path("entityRegistry").has("moneyCases"));
        var array = context.path("entityRegistry").path("moneyCases");
        assertEquals(1, array.size());
        assertEquals("MC_12345678901234567890123456789012", array.get(0).path("moneyCaseNo").asText());
        assertEquals("T202610310001", array.get(0).path("orderNo").asText());
    }
}

