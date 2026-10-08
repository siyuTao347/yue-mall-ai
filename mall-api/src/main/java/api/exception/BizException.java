package api.exception;

import java.util.Map;

/**
 * 业务异常基类：必须携带稳定错误码与 HTTP 语义，Service 层不拼接用户提示协议。
 */
public abstract class BizException extends RuntimeException {

    private final int httpStatus;
    private final String errorCode;
    private final Map<String, Object> context;

    protected BizException(int httpStatus, String errorCode, String message) {
        this(httpStatus, errorCode, message, Map.of());
    }

    protected BizException(int httpStatus, String errorCode, String message, Map<String, Object> context) {
        super(message);
        this.httpStatus = httpStatus;
        this.errorCode = errorCode;
        this.context = context == null ? Map.of() : Map.copyOf(context);
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public Map<String, Object> getContext() {
        return context;
    }
}
