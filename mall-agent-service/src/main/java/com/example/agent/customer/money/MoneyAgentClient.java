package com.example.agent.customer.money;

import api.agent.money.contract.*;

/**
 * 客服调用 Money 资金调查微服务的应用层接口
 * <p>
 * 遵循 A2A 协议分离原则，仅定义业务操作；底层网络协议由传输实现承接。
 * </p>
 */
public interface MoneyAgentClient {

    /**
     * 发起资金异常调查 (映射为 A2A SendMessage + mall.money.investigation-request.v1)
     */
    MoneyTaskReceipt start(DelegatedRequest<MoneyInvestigationRequest> request);

    /**
     * 查询指定调查任务视图 (映射为 A2A GetTask)
     */
    MoneyTaskView get(MoneyTaskReference ref, MoneyCallerCredential credential);

    /**
     * 查询资金案件当前视图 (映射为 A2A SendMessage + money.case-status.v1)
     */
    MoneyCaseView getCase(MoneyCaseReference ref, MoneyCallerCredential credential);

    /**
     * 提交补充调查材料 (映射为 A2A SendMessage + mall.money.additional-input.v1)
     */
    MoneyTaskView provideInput(DelegatedRequest<MoneyAdditionalInput> input);

    /**
     * 取消调查任务 (映射为 A2A CancelTask + mall.money.control.v1)
     */
    MoneyCancelReceipt cancel(DelegatedRequest<MoneyCancelRequest> request);

    // =========================================================================
    // 兼容原有简版调用的便捷默认方法与 DTO
    // =========================================================================

    default MoneyStartResponse start(String orderNo, String problemFamily, String targetRef, Long userId) {
        throw new UnsupportedOperationException("请使用标准 start(DelegatedRequest<MoneyInvestigationRequest>) 接口");
    }

    default MoneyCaseResponse getCase(String caseNo) {
        throw new UnsupportedOperationException("请使用标准 getCase(MoneyCaseReference, MoneyCallerCredential) 接口");
    }

    record MoneyStartResponse(String caseNo, String taskNo, String caseState, String taskState, String outcome, boolean isNew) {}
    record MoneyCaseResponse(String caseNo, String caseState, String outcome, String reportNo, String factsJson) {}
}
