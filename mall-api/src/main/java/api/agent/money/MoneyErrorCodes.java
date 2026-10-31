package api.agent.money;

/**
 * 资金异常调查业务错误码定义
 */
public final class MoneyErrorCodes {
    private MoneyErrorCodes() {}

    public static final String MONEY_UNAUTHORIZED = "MONEY_UNAUTHORIZED";
    public static final String MONEY_FORBIDDEN = "MONEY_FORBIDDEN";
    public static final String MONEY_SCOPE_MISMATCH = "MONEY_SCOPE_MISMATCH";
    public static final String MONEY_SUBJECT_NOT_FOUND = "MONEY_SUBJECT_NOT_FOUND";
    public static final String MONEY_SUBJECT_AMBIGUOUS = "MONEY_SUBJECT_AMBIGUOUS";
    public static final String MONEY_CHANNEL_UNAVAILABLE = "MONEY_CHANNEL_UNAVAILABLE";
    public static final String MONEY_CHANNEL_TIMEOUT = "MONEY_CHANNEL_TIMEOUT";
    public static final String MONEY_PROFILE_UNRESOLVED = "MONEY_PROFILE_UNRESOLVED";
    public static final String MONEY_AMOUNT_MISMATCH = "MONEY_AMOUNT_MISMATCH";
    public static final String MONEY_CURRENCY_MISMATCH = "MONEY_CURRENCY_MISMATCH";
    public static final String MONEY_MERCHANT_MISMATCH = "MONEY_MERCHANT_MISMATCH";
    public static final String MONEY_RECON_DIFF_NOT_FOUND = "MONEY_RECON_DIFF_NOT_FOUND";
    public static final String MONEY_SCOPE_PROOF_INVALID = "MONEY_SCOPE_PROOF_INVALID";
    public static final String MONEY_SCOPE_PROOF_EXPIRED = "MONEY_SCOPE_PROOF_EXPIRED";
    public static final String MONEY_LEGACY_UNLINKED = "MONEY_LEGACY_UNLINKED";
    public static final String MONEY_IDEMPOTENCY_CONFLICT = "MONEY_IDEMPOTENCY_CONFLICT";
}

