package com.example.agent.customer.conversation;

import api.agent.AgentActor;
import com.example.agent.customer.CustomerDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CustomerSessionAccessServiceTest {

    private CustomerDatabase db;
    private CustomerSessionAccessService accessService;

    @BeforeEach
    void setUp() {
        String dbName = "mem:session_access_test_" + UUID.randomUUID().toString().replace("-", "");
        DriverManagerDataSource dataSource = new DriverManagerDataSource("jdbc:h2:" + dbName + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        db = new CustomerDatabase(dataSource, dataSource);

        db.mysql.execute("CREATE TABLE t_agent_session (session_no VARCHAR(64) PRIMARY KEY, user_id BIGINT, status VARCHAR(24), service_mode VARCHAR(24), control_epoch BIGINT);");
        db.mysql.update("INSERT INTO t_agent_session(session_no, user_id, status, service_mode, control_epoch) VALUES('S1', 100, 'ACTIVE', 'BOT', 1)");
        db.mysql.update("INSERT INTO t_agent_session(session_no, user_id, status, service_mode, control_epoch) VALUES('S_CLOSED', 100, 'CLOSED', 'BOT', 1)");

        accessService = new CustomerSessionAccessService(db);
    }

    @Test
    void validateAndGetSession_unauthenticated_throwsException() {
        assertThrows(RuntimeException.class, () -> accessService.validateAndGetSession(null, "S1"));
        assertThrows(RuntimeException.class, () -> accessService.validateAndGetSession(new AgentActor(0L, "USER"), "S1"));
    }

    @Test
    void validateAndGetSession_blankSession_throwsException() {
        assertThrows(RuntimeException.class, () -> accessService.validateAndGetSession(new AgentActor(100L, "USER"), ""));
    }

    @Test
    void validateAndGetSession_ownerMatches_returnsSessionInfo() {
        AgentActor owner = new AgentActor(100L, "USER");
        var info = accessService.validateAndGetSession(owner, "S1");
        assertNotNull(info);
        assertEquals("S1", info.sessionNo());
        assertEquals(100L, info.userId());
    }

    @Test
    void validateAndGetSession_otherUser_throwsForbidden() {
        AgentActor other = new AgentActor(200L, "USER");
        var ex = assertThrows(RuntimeException.class, () -> accessService.validateAndGetSession(other, "S1"));
        assertTrue(ex.getMessage().contains("无法访问他人会话"));
    }

    @Test
    void validateAndGetSession_staffRole_allowsAccessToAnySession() {
        AgentActor staff = new AgentActor(999L, "STAFF");
        var info = accessService.validateAndGetSession(staff, "S1");
        assertNotNull(info);
        assertEquals("S1", info.sessionNo());
    }

    @Test
    void validateActiveSession_closedSession_throwsSessionInactive() {
        AgentActor owner = new AgentActor(100L, "USER");
        assertThrows(RuntimeException.class, () -> accessService.validateActiveSession(owner, "S_CLOSED"));
    }
}
