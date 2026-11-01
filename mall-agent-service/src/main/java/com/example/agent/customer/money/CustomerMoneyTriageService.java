package com.example.agent.customer.money;

import api.agent.money.MoneyProblemFamily;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * 客服端资金问题智能分流服务 (CustomerMoneyTriageService)
 * <p>
 * 在登记或发起资金调查前，读取最小支付/退款摘要与已有活动案，决定分流决策：
 * EXPLAIN_DIRECTLY / ATTACH_EXISTING / REGISTER_INVESTIGATION / ASK_SCOPE / HANDOFF。
 * 避免无条件向服务端打长调查请求。
 * </p>
 */
@Slf4j
@Service
public class CustomerMoneyTriageService {

    private final AgentMoneyBindingMapper bindingMapper;

    private static final Pattern ESCALATE_PATTERN = Pattern.compile(
            "(?i)(?:盗号|被盗|报警|欺诈|起诉|立案|仲裁|诈骗|人身威胁|赔偿精神损失)"
    );

    public CustomerMoneyTriageService(AgentMoneyBindingMapper bindingMapper) {
        this.bindingMapper = bindingMapper;
    }

    public enum TriageDecision {
        EXPLAIN_DIRECTLY,
        ATTACH_EXISTING,
        REGISTER_INVESTIGATION,
        ASK_SCOPE,
        HANDOFF
    }

    public record TriageResult(
            TriageDecision decision,
            MoneyProblemFamily problemFamily,
            String targetRef,
            String existingCaseNo,
            String explanation
    ) {}

    public TriageResult triage(Long userId, String sessionNo, String intent, Map<String, String> slots, String userQuestion) {
        String safeQuestion = userQuestion != null ? userQuestion : "";

        // 1. 检查是否触碰人工升级高危红线 (被盗/欺诈/仲裁等)
        if (ESCALATE_PATTERN.matcher(safeQuestion).find()) {
            return new TriageResult(TriageDecision.HANDOFF, null, null, null,
                    "检测到高危安全诉求，转交人工专席处理");
        }

        String orderNo = slots != null ? slots.get("orderNo") : null;
        String caseNo = slots != null ? slots.get("moneyCaseNo") : null;
        String attemptNo = slots != null ? slots.get("attemptNo") : null;
        String refundNo = slots != null ? slots.get("refundNo") : null;

        // 2. 检查是否有明确业务范围
        if ((orderNo == null || orderNo.isBlank()) && (caseNo == null || caseNo.isBlank())) {
            return new TriageResult(TriageDecision.ASK_SCOPE, null, null, null,
                    "请提供您需要核对资金异常的订单编号或案件编号");
        }

        Long uid = userId != null ? userId : 0L;

        // 3. 检查是否存在活动中的案件绑定 (覆盖已有进行中案件)
        if (orderNo != null && !orderNo.isBlank()) {
            AgentMoneyBindingEntity existing = bindingMapper.selectOne(new LambdaQueryWrapper<AgentMoneyBindingEntity>()
                    .eq(AgentMoneyBindingEntity::getUserId, uid)
                    .eq(AgentMoneyBindingEntity::getOrderNo, orderNo.trim())
                    .in(AgentMoneyBindingEntity::getCaseState, "OPEN", "NEED_INPUT", "NEED_REVIEW", "WAITING_BIZ")
                    .last("LIMIT 1"));
            if (existing != null) {
                return new TriageResult(TriageDecision.ATTACH_EXISTING, null, orderNo.trim(), existing.getCaseNo(),
                        "该订单已有正在处理中的资金案件: " + existing.getCaseNo());
            }
        }

        // 4. 精确判断问题族 (problemFamily)
        MoneyProblemFamily problemFamily;
        if ("REFUND_ANOMALY".equalsIgnoreCase(intent) || safeQuestion.contains("退款")) {
            problemFamily = MoneyProblemFamily.REFUND_STATUS;
        } else if (safeQuestion.contains("多扣") || safeQuestion.contains("重复扣") || safeQuestion.contains("迟到扣") || safeQuestion.contains("多付")) {
            problemFamily = MoneyProblemFamily.EXCESS_PAYMENT;
        } else if (safeQuestion.contains("对账") || (orderNo != null && orderNo.startsWith("DIFF_"))) {
            problemFamily = MoneyProblemFamily.RECON_MISMATCH;
        } else {
            problemFamily = MoneyProblemFamily.PAYMENT_CONFIRMATION;
        }

        // 5. 确定目标引用 (targetRef)：不默认退化为订单号，优先精确 attempt / refund
        String targetRef;
        if (attemptNo != null && !attemptNo.isBlank()) {
            targetRef = attemptNo.trim();
        } else if (refundNo != null && !refundNo.isBlank()) {
            targetRef = refundNo.trim();
        } else if (orderNo != null && !orderNo.isBlank()) {
            targetRef = orderNo.trim();
        } else {
            targetRef = caseNo != null ? caseNo.trim() : "UNKNOWN";
        }

        return new TriageResult(TriageDecision.REGISTER_INVESTIGATION, problemFamily, targetRef, null,
                "判定为资金异常，符合发起调查条件");
    }
}

