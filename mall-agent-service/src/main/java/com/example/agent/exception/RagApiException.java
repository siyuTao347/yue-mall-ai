package com.example.agent.exception;

/** RAG 管理端业务异常：携带 HTTP 语义与稳定错误码。 */
public class RagApiException extends RuntimeException {

    private final int httpStatus;
    private final String errorCode;

    public RagApiException(int httpStatus, String errorCode, String message) {
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

    public static RagApiException badRequest(String errorCode, String message) {
        return new RagApiException(400, errorCode, message);
    }

    public static RagApiException notFound(String errorCode, String message) {
        return new RagApiException(404, errorCode, message);
    }

    public static RagApiException conflict(String errorCode, String message) {
        return new RagApiException(409, errorCode, message);
    }

    public static RagApiException unavailable(String errorCode, String message) {
        return new RagApiException(503, errorCode, message);
    }
}
