package api.agent.money.contract;

/**
 * 资金 Agent 远程调用及契约业务异常
 * <p>
 * 保留明确的错误代码、重试标志和调用链追踪 ID
 * </p>
 */
public class MoneyAgentException extends RuntimeException {

    private final String code;
    private final boolean retryable;
    private final String traceId;
    private final int rpcCode;

    public MoneyAgentException(String code, String message) {
        this(code, message, false, null, -32000, null);
    }

    public MoneyAgentException(String code, String message, boolean retryable) {
        this(code, message, retryable, null, -32000, null);
    }

    public MoneyAgentException(String code, String message, boolean retryable, String traceId) {
        this(code, message, retryable, traceId, -32000, null);
    }

    public MoneyAgentException(String code, String message, boolean retryable, String traceId, int rpcCode) {
        this(code, message, retryable, traceId, rpcCode, null);
    }

    public MoneyAgentException(String code, String message, boolean retryable, String traceId, int rpcCode, Throwable cause) {
        super(message, cause);
        this.code = code != null ? code : "INTERNAL_ERROR";
        this.retryable = retryable;
        this.traceId = traceId;
        this.rpcCode = rpcCode;
    }

    public String getCode() {
        return code;
    }

    public boolean isRetryable() {
        return retryable;
    }

    public String getTraceId() {
        return traceId;
    }

    public int getRpcCode() {
        return rpcCode;
    }

    @Override
    public String toString() {
        return "MoneyAgentException{" +
                "code='" + code + '\'' +
                ", message='" + getMessage() + '\'' +
                ", retryable=" + retryable +
                ", traceId='" + traceId + '\'' +
                ", rpcCode=" + rpcCode +
                '}';
    }
}

