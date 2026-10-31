package api.agent.money;

/**
 * 资金异常问题分类
 */
public enum MoneyProblemFamily {
    /** 支付已扣款但平台未确认/未采用 */
    PAYMENT_CONFIRMATION,
    /** 重复/迟到支付与挂账异常 */
    EXCESS_PAYMENT,
    /** 退款状态冲突或长时间未确认 */
    REFUND_STATUS,
    /** 对账金额/单边差异 */
    RECON_MISMATCH
}

