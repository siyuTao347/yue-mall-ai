package com.example.risk.exception;

/**
 * 风控工作台稳定错误码异常，统一由 RiskCaseAdminController 转换为
 * {code,msg,errorCode,traceId} 响应。
 */
public class RiskApiException extends RuntimeException {
    public static final String STATE_CHANGED = "RISK_CASE_STATE_CHANGED";
    public static final String CASE_NOT_FOUND = "RISK_CASE_NOT_FOUND";
    public static final String COMMAND_NOT_FOUND = "RISK_COMMAND_NOT_FOUND";
    public static final String INVALID_OPERATION = "RISK_CASE_INVALID_OPERATION";
    public static final String CONFLICT = "RISK_CASE_OPERATION_CONFLICT";
    public static final String MANUAL_COMPLETE_DISABLED = "RISK_MANUAL_COMPLETE_DISABLED";

    private final int httpStatus;
    private final String errorCode;

    public RiskApiException(int httpStatus, String errorCode, String message) {
        super(message);
        this.httpStatus = httpStatus;
        this.errorCode = errorCode;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String errorCode() {
        return errorCode;
    }

    public static RiskApiException badRequest(String errorCode, String message) {
        return new RiskApiException(400, errorCode, message);
    }

    public static RiskApiException notFound(String errorCode, String message) {
        return new RiskApiException(404, errorCode, message);
    }

    public static RiskApiException forbidden(String errorCode, String message) {
        return new RiskApiException(403, errorCode, message);
    }

    public static RiskApiException conflict(String errorCode, String message) {
        return new RiskApiException(409, errorCode, message);
    }

    public static RiskApiException stateChanged() {
        return conflict(STATE_CHANGED, "案件状态已变化，请刷新后重试");
    }
}
