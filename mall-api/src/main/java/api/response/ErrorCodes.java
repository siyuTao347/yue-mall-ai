package api.response;

/**
 * 稳定错误码集中定义，格式：{MODULE}_{DOMAIN}_{ACTION}_{REASON}。
 * <p>错误码一旦对外使用，不得修改含义；前端只根据错误码决定交互，不解析中文文案。</p>
 */
public final class ErrorCodes {

    private ErrorCodes() {
    }

    // ---------- common ----------
    public static final String COMMON_REQUEST_PARAM_INVALID = "COMMON_REQUEST_PARAM_INVALID";
    public static final String COMMON_UNAUTHORIZED = "COMMON_UNAUTHORIZED";
    public static final String COMMON_FORBIDDEN = "COMMON_FORBIDDEN";
    public static final String COMMON_NOT_FOUND = "COMMON_NOT_FOUND";
    public static final String COMMON_STATE_CONFLICT = "COMMON_STATE_CONFLICT";
    public static final String COMMON_REMOTE_DEPENDENCY_ERROR = "COMMON_REMOTE_DEPENDENCY_ERROR";
    public static final String COMMON_SYSTEM_ERROR = "COMMON_SYSTEM_ERROR";

    // ---------- trade order ----------
    public static final String TRADE_ORDER_NOT_FOUND = "TRADE_ORDER_NOT_FOUND";
    public static final String TRADE_ORDER_OWNER_MISMATCH = "TRADE_ORDER_OWNER_MISMATCH";
    public static final String TRADE_ORDER_CREATE_STOCK_INSUFFICIENT = "TRADE_ORDER_CREATE_STOCK_INSUFFICIENT";
    public static final String TRADE_ORDER_STATE_INVALID = "TRADE_ORDER_STATE_INVALID";

    // ---------- trade payment ----------
    public static final String TRADE_PAYMENT_NOT_FOUND = "TRADE_PAYMENT_NOT_FOUND";
    public static final String TRADE_PAYMENT_CALLBACK_SIGNATURE_INVALID =
            "TRADE_PAYMENT_CALLBACK_SIGNATURE_INVALID";
    public static final String TRADE_PAYMENT_CALLBACK_NONCE_DUPLICATED =
            "TRADE_PAYMENT_CALLBACK_NONCE_DUPLICATED";

    // ---------- trade dispute ----------
    public static final String TRADE_DISPUTE_NOT_FOUND = "TRADE_DISPUTE_NOT_FOUND";
    public static final String TRADE_DISPUTE_ALREADY_RESOLVED = "TRADE_DISPUTE_ALREADY_RESOLVED";

    // ---------- risk ----------
    public static final String RISK_CASE_STATE_CHANGED = "RISK_CASE_STATE_CHANGED";
    public static final String RISK_COMMAND_DEAD_LETTER = "RISK_COMMAND_DEAD_LETTER";
}
