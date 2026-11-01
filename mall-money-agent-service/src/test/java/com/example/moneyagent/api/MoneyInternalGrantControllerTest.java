package com.example.moneyagent.api;

import api.agent.money.MoneySecuritySupport;
import api.response.ApiResponse;
import com.example.moneyagent.application.MoneyGrantService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MoneyInternalGrantControllerTest {

    private MoneyGrantService grantService;
    private MoneyInternalGrantController controller;

    @BeforeEach
    void setUp() {
        grantService = mock(MoneyGrantService.class);
        controller = new MoneyInternalGrantController(grantService);
    }

    @Test
    @DisplayName("测试撤回 Grant 接口成功")
    void testRevokeGrantSuccess() {
        String svcToken = MoneySecuritySupport.createServiceIdentityProof("mall-agent-service", "mall-money-services");
        when(grantService.revokeGrant("GRANT_01", "用户撤回授权", 1L))
                .thenReturn(new MoneyGrantService.GrantRevocationResult("GRANT_01", true, 2, "撤回成功"));

        ApiResponse<MoneyGrantService.GrantRevocationResult> resp = controller.revokeGrant(
                "GRANT_01",
                "Bearer " + svcToken,
                Map.of("reason", "用户撤回授权", "expectedGrantVersion", 1)
        );

        assertNotNull(resp);
        assertEquals(200, resp.code());
        assertTrue(resp.data().success());
        assertEquals(2, resp.data().affectedTasks());
    }
}
