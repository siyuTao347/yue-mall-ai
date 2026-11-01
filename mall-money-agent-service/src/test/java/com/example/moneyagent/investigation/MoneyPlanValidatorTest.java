package com.example.moneyagent.investigation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MoneyPlanValidatorTest {

    private MoneyPlanValidator validator;

    @BeforeEach
    void setUp() {
        validator = new MoneyPlanValidator();
    }

    @Test
    @DisplayName("测试空规划或无进一步动作的有效性")
    void testEmptyOrNoActionPlan() {
        MoneyPlanValidator.ValidationResult nullRes = validator.validatePlan(null, "ORDER:T1001", "T1001", List.of("FACT_01"));
        assertFalse(nullRes.valid());
        assertTrue(nullRes.rejectReason().contains("为空"));

        ModelPlanResult noAction = ModelPlanResult.builder().actionNeeded(false).reason("无需动作").build();
        MoneyPlanValidator.ValidationResult noActionRes = validator.validatePlan(noAction, "ORDER:T1001", "T1001", List.of("FACT_01"));
        assertTrue(noActionRes.valid());
        assertNull(noActionRes.rejectReason());
    }

    @Test
    @DisplayName("测试工具白名单限制：非白名单工具被拒绝")
    void testDisallowedToolRejected() {
        ModelPlanResult plan = ModelPlanResult.builder()
                .actionNeeded(true)
                .toolName("ARBITRARY_SHELL_COMMAND")
                .targetRef("T1001")
                .build();

        MoneyPlanValidator.ValidationResult res = validator.validatePlan(plan, "ORDER:T1001", "T1001", List.of());
        assertFalse(res.valid());
        assertTrue(res.rejectReason().contains("不在受信任目录中"));
    }

    @Test
    @DisplayName("测试授权范围与目标匹配：越权目标被拒绝")
    void testUnauthorizedTargetRejected() {
        ModelPlanResult plan = ModelPlanResult.builder()
                .actionNeeded(true)
                .toolName("OBSERVE_CHANNEL")
                .targetRef("OTHER_USER_ORDER_9999")
                .build();

        MoneyPlanValidator.ValidationResult res = validator.validatePlan(plan, "ORDER:T1001", "T1001", List.of());
        assertFalse(res.valid());
        assertTrue(res.rejectReason().contains("超出授权范围"));
    }

    @Test
    @DisplayName("测试事实支撑 ID 校验：引用未验证事实被拒绝")
    void testUnverifiedFactIdRejected() {
        ModelPlanResult plan = ModelPlanResult.builder()
                .actionNeeded(true)
                .toolName("OBSERVE_CHANNEL")
                .targetRef("T1001")
                .supportingFactIds(List.of("VALID_FACT_01", "HALLUCINATED_FACT_99"))
                .build();

        MoneyPlanValidator.ValidationResult res = validator.validatePlan(plan, "ORDER:T1001", "T1001", List.of("VALID_FACT_01"));
        assertFalse(res.valid());
        assertTrue(res.rejectReason().contains("未验证的事实 ID"));
    }

    @Test
    @DisplayName("测试白名单内工具且目标匹配的正常通过")
    void testValidPlanApproved() {
        ModelPlanResult plan = ModelPlanResult.builder()
                .actionNeeded(true)
                .toolName("OBSERVE_CHANNEL")
                .targetRef("T1001")
                .supportingFactIds(List.of("VALID_FACT_01"))
                .build();

        MoneyPlanValidator.ValidationResult res = validator.validatePlan(plan, "ORDER:T1001", "T1001", List.of("VALID_FACT_01"));
        assertTrue(res.valid());
        assertNull(res.rejectReason());
    }
}

