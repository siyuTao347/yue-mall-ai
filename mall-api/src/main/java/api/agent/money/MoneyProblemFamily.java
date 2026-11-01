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
    RECON_MISMATCH;

    public static MoneyProblemFamily fromString(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("问题分类不能为空");
        }
        String normalized = raw.trim().toUpperCase();
        for (MoneyProblemFamily family : values()) {
            if (family.name().equals(normalized)) {
                return family;
            }
        }
        throw new IllegalArgumentException("未知问题分类: " + raw + "，首版仅支持: PAYMENT_CONFIRMATION, EXCESS_PAYMENT, REFUND_STATUS, RECON_MISMATCH");
    }

    public static boolean isValid(String raw) {
        if (raw == null || raw.isBlank()) return false;
        try {
            fromString(raw);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
