package com.example.agent.customer.richui;

import api.agent.AgentActor;
import com.example.agent.customer.CustomerDatabase;
import com.example.agent.customer.conversation.CustomerSessionAccessService;
import com.example.agent.customer.conversation.SessionEventStore;
import com.example.agent.customer.money.AgentMoneyCardEntity;
import com.example.agent.customer.money.AgentMoneyCardMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class CardActionServiceTest {

    private CustomerDatabase db;
    private SessionEventStore eventStore;
    private CustomerSessionAccessService sessionAccessService;
    private AgentMoneyCardMapper cardMapper;
    private CardActionService cardActionService;

    @BeforeEach
    void setUp() {
        String dbName = "mem:card_action_test_" + UUID.randomUUID().toString().replace("-", "");
        DriverManagerDataSource dataSource = new DriverManagerDataSource("jdbc:h2:" + dbName + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        db = new CustomerDatabase(dataSource, dataSource);

        db.mysql.execute("CREATE TABLE t_agent_session (session_no VARCHAR(64) PRIMARY KEY, user_id BIGINT, status VARCHAR(24), service_mode VARCHAR(24), control_epoch BIGINT, active_handoff_no VARCHAR(64));");
        db.mysql.execute("CREATE TABLE t_agent_feedback (session_no VARCHAR(64), feedback_scope VARCHAR(24), target_no VARCHAR(64), user_id BIGINT, rating INT, comment VARCHAR(512), PRIMARY KEY(session_no, feedback_scope, target_no));");
        db.mysql.execute("CREATE TABLE t_agent_handoff (session_no VARCHAR(64), status VARCHAR(24));");

        db.mysql.update("INSERT INTO t_agent_session(session_no, user_id, status, service_mode, control_epoch) VALUES('S1', 100, 'ACTIVE', 'BOT', 1)");

        eventStore = mock(SessionEventStore.class);
        sessionAccessService = mock(CustomerSessionAccessService.class);
        cardMapper = mock(AgentMoneyCardMapper.class);
        cardActionService = new CardActionService(db, eventStore, sessionAccessService, cardMapper, new ObjectMapper());
    }

    @Test
    void executeAction_unknownAction_throwsUnsupportedException() {
        AgentActor actor = new AgentActor(100L, "USER");
        var ex = assertThrows(RuntimeException.class, () ->
                cardActionService.executeAction(actor, "S1", "CARD_01", "ACT_01", 1, "UNKNOWN_FOO_ACTION", null, "OP_01")
        );
        assertTrue(ex.getMessage().contains("不支持的卡片动作"));
    }

    @Test
    void executeAction_cancelHandoff_succeeds() {
        AgentActor actor = new AgentActor(100L, "USER");
        var res = cardActionService.executeAction(actor, "S1", "CARD_01", "ACT_01", 1, "CANCEL_HANDOFF", null, "OP_01");
        assertNotNull(res);
        assertEquals("SUCCEEDED", res.path("status").asText());
        assertEquals(2, res.path("revision").asInt());
        verify(eventStore).appendPublicEventInTx(eq("S1"), anyString(), any());
    }

    @Test
    void executeAction_cardRevisionConflict_throwsConflict() {
        AgentMoneyCardEntity card = AgentMoneyCardEntity.builder()
                .cardId("CARD_01")
                .userId(100L)
                .sessionNo("S1")
                .revision(5L) // database has revision 5
                .build();
        when(cardMapper.selectOne(any())).thenReturn(card);

        AgentActor actor = new AgentActor(100L, "USER");
        // Client passed revision 1 (stale)
        assertThrows(RuntimeException.class, () ->
                cardActionService.executeAction(actor, "S1", "CARD_01", "ACT_01", 1, "OPEN_MONEY_CASE", null, "OP_01")
        );
    }
}
