package com.example.moneyagent.investigation;

import api.agent.money.MoneySecuritySupport;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class MockMoneyModelClient implements MoneyModelClient {

    private volatile String defaultScenario = "NO_FURTHER_ACTION";

    public void setScenario(String scenario) {
        this.defaultScenario = scenario;
    }

    @Override
    public ModelPlanResult planNextAction(ModelPlanContext context) {
        String activeScenario = context.getNamedScenario() != null ? context.getNamedScenario() : this.defaultScenario;

        if ("SUCCESS_PLAN".equalsIgnoreCase(activeScenario)) {
            String tool = "OBSERVE_CHANNEL";
            String target = context.getTargetRef();
            String hash = MoneySecuritySupport.computeFingerprint(tool + ":" + target);
            if (context.getCompletedActionHashes() != null && context.getCompletedActionHashes().contains(hash)) {
                return ModelPlanResult.builder().actionNeeded(false).reason("已执行该动作").build();
            }
            return ModelPlanResult.builder()
                    .actionNeeded(true)
                    .toolName(tool)
                    .targetRef(target)
                    .supportingFactIds(List.of("FACT_ORDER_01"))
                    .hypothesis("渠道实际出账可能与平台订单状态不同步")
                    .reason("需要查询外部渠道事实以验证真实到账状态")
                    .actionHash(hash)
                    .build();
        } else if ("UNAUTHORIZED_TARGET".equalsIgnoreCase(activeScenario)) {
            return ModelPlanResult.builder()
                    .actionNeeded(true)
                    .toolName("OBSERVE_CHANNEL")
                    .targetRef("T_UNAUTHORIZED_OTHER_USER_ORDER_9999")
                    .supportingFactIds(List.of("FACT_UNKNOWN"))
                    .hypothesis("测试越权目标规划")
                    .reason("测试")
                    .actionHash("UNAUTHORIZED_HASH")
                    .build();
        } else if ("UNKNOWN_FACT_ID".equalsIgnoreCase(activeScenario)) {
            return ModelPlanResult.builder()
                    .actionNeeded(true)
                    .toolName("OBSERVE_CHANNEL")
                    .targetRef(context.getTargetRef())
                    .supportingFactIds(List.of("NON_EXISTENT_FACT_ID_9999"))
                    .hypothesis("测试未知事实 ID")
                    .reason("测试")
                    .actionHash("UNKNOWN_FACT_HASH")
                    .build();
        } else if ("REQUEST_INPUT_SCENARIO".equalsIgnoreCase(activeScenario)) {
            return ModelPlanResult.builder()
                    .actionNeeded(true)
                    .toolName("REQUEST_INPUT")
                    .targetRef(context.getTargetRef())
                    .supportingFactIds(List.of())
                    .hypothesis("缺少持卡人支付凭证截图")
                    .reason("需要用户补充银行扣款回单截图")
                    .actionHash(MoneySecuritySupport.computeFingerprint("REQUEST_INPUT:" + context.getTargetRef()))
                    .build();
        } else if ("BUDGET_EXHAUSTED".equalsIgnoreCase(activeScenario)) {
            return ModelPlanResult.builder()
                    .actionNeeded(false)
                    .reason("模型规划预算或轮次已耗尽")
                    .build();
        } else {
            // NO_FURTHER_ACTION: 直接进入汇总报告阶段
            return ModelPlanResult.builder()
                    .actionNeeded(false)
                    .reason("已有规则与事实充分，无需进一步动作")
                    .build();
        }
    }
}

