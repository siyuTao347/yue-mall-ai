package api.agent.money;

/**
 * 四层资金事实
 */
public enum MoneyFactLayer {
    /** 业务层：订单/售后/交易编排记录 */
    BUSINESS,
    /** 平台账本层：User 服务的资金事务与流水 */
    PLATFORM_LEDGER,
    /** 支付渠道层：渠道网关只读观察与对账单流水 */
    CHANNEL,
    /** 收款方到账层：买家/商户外部账户实际收款证据（首版不支持，通常为 UNKNOWN） */
    BENEFICIARY_RECEIPT
}

