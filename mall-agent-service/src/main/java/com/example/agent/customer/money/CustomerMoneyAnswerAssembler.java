package com.example.agent.customer.money;

import api.agent.money.contract.MoneyCaseView;
import api.agent.money.contract.MoneyTaskReceipt;
import api.agent.money.contract.MoneyTaskView;
import org.springframework.stereotype.Component;

/**
 * 资金段确定性答复拼装器
 * 严格依据权威条件生成不可改写资金段，遵守表述白名单，禁止伪造退款承诺或到账保证
 */
@Component
public class CustomerMoneyAnswerAssembler {

    /**
     * 针对受理登记结果组装资金段答复
     */
    public String assembleDispatchAccepted(String orderNo, MoneyTaskReceipt receipt) {
        String safeOrder = orderNo != null ? orderNo : "UNKNOWN";
        String caseNo = receipt != null ? receipt.getCaseNo() : null;
        if (caseNo != null && !caseNo.isBlank()) {
            return String.format("已为您正式受理订单 %s 的资金核实与调查请求（案件编号：%s）。\n" +
                    "平台正通过安全通道核实支付流水与对账记录，核实完毕后将自动汇报结论。", safeOrder, caseNo);
        }
        return String.format("已登记您的资金核查请求（订单：%s），正由后台安全队列异步提交受理，请稍后关注案件进展通知。", safeOrder);
    }

    /**
     * 针对提交中/待重试组装答复
     */
    public String assembleDispatchPending(String orderNo, String dispatchNo) {
        String safeOrder = orderNo != null ? orderNo : "UNKNOWN";
        return String.format("已登记您的资金核实请求（订单：%s，登记编号：%s），系统正提交资金核实，后台将持续处理并在有进展时通知您。",
                safeOrder, dispatchNo != null ? dispatchNo : "");
    }

    /**
     * 针对已有案件视图组装答复
     */
    public String assembleCaseView(String orderNo, MoneyCaseView caseView) {
        String safeOrder = orderNo != null ? orderNo : "UNKNOWN";
        if (caseView == null) {
            return String.format("正在查询订单 %s 的资金核实情况，当前暂未获取到最新进展。", safeOrder);
        }

        String caseNo = caseView.getCaseNo() != null ? caseView.getCaseNo() : "UNKNOWN";
        String caseState = caseView.getCaseState() != null ? caseView.getCaseState() : "OPEN";
        String taskState = caseView.getTaskState() != null ? caseView.getTaskState() : "WORKING";
        String outcome = caseView.getResolutionOutcome();

        if ("NEED_INPUT".equalsIgnoreCase(caseState) || "INPUT_REQUIRED".equalsIgnoreCase(taskState)) {
            return String.format("您的资金调查案件（编号：%s，关联订单：%s）需要补充相关凭证，请点击下方卡片提供资料。",
                    caseNo, safeOrder);
        }

        if ("NEED_REVIEW".equalsIgnoreCase(caseState)) {
            return String.format("您的资金调查案件（编号：%s，关联订单：%s）调查材料已核查整理完毕，正在等待财务专员人工复核确认。",
                    caseNo, safeOrder);
        }

        if ("WAITING_BIZ".equalsIgnoreCase(caseState)) {
            return String.format("您的资金调查案件（编号：%s，关联订单：%s）已提交业务系统处理，正在等待业务处理回执。",
                    caseNo, safeOrder);
        }

        if ("RESOLVED".equalsIgnoreCase(caseState) || "CLOSED".equalsIgnoreCase(caseState)) {
            return String.format("您的资金调查案件（编号：%s，关联订单：%s）调查已完成，结论：%s。",
                    caseNo, safeOrder, friendlyOutcome(outcome));
        }

        if ("WORKING".equalsIgnoreCase(taskState) || "SUBMITTED".equalsIgnoreCase(taskState)) {
            return String.format("您的资金调查案件（编号：%s，关联订单：%s）正在核实调查中，当前状态：%s。",
                    caseNo, safeOrder, friendlyOutcome(outcome));
        }

        return String.format("您的资金调查案件（编号：%s，关联订单：%s）最新状态：%s。",
                caseNo, safeOrder, friendlyOutcome(outcome));
    }

    /**
     * 针对已有绑定组装答复
     */
    public String assembleBindingStatus(AgentMoneyBindingEntity binding, String outcome) {
        String caseNo = binding.getCaseNo();
        String orderNo = binding.getOrderNo();
        String caseState = binding.getCaseState();

        if ("NEED_INPUT".equalsIgnoreCase(caseState)) {
            return String.format("您的资金调查案件（编号：%s，关联订单：%s）需要补充相关凭证，请提供补充资料。", caseNo, orderNo);
        }
        if ("NEED_REVIEW".equalsIgnoreCase(caseState)) {
            return String.format("您的资金调查案件（编号：%s，关联订单：%s）调查材料已核查整理完毕，等待财务专员人工复核确认。", caseNo, orderNo);
        }
        return String.format("您的资金调查案件（编号：%s，关联订单：%s）最新进展：%s。", caseNo, orderNo, friendlyOutcome(outcome));
    }

    public static String friendlyOutcome(String outcome) {
        if (outcome == null || outcome.isBlank() || "UNDETERMINED".equalsIgnoreCase(outcome)) {
            return "调查核实中";
        }
        return switch (outcome) {
            case "VERIFIED_NORMAL" -> "经核实资金状态正常，未见异常扣款或退款失败";
            case "VERIFIED_ERROR" -> "经核验存在资金差异，已提交财务与渠道核实处理";
            case "POTENTIAL_OVERPAY" -> "核对发现可能存在多次扣款或超额支付，正在协调渠道对账";
            case "POTENTIAL_REFUND_FAIL" -> "退款流程已受理，渠道结果待进一步核实确认";
            case "NEEDS_REVIEW" -> "调查材料已整理，等待财务专员人工复核确认";
            case "INCONCLUSIVE" -> "当前数据源暂未完全覆盖，等待进一步核实凭证";
            default -> outcome;
        };
    }
}

