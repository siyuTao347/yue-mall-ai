package api.response;

import api.context.TraceContext;

/**
 * 统一响应外壳：{code, msg, data, errorCode, traceId}。
 * <p>保持既有 {code, msg, data} 字段兼容，errorCode/traceId 为新增字段，前端可忽略。</p>
 */
public record ApiResponse<T>(int code, String msg, T data, String errorCode, String traceId) {

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(200, "success", data, null, TraceContext.getTraceId());
    }

    public static <T> ApiResponse<T> success(String msg, T data) {
        return new ApiResponse<>(200, msg, data, null, TraceContext.getTraceId());
    }

    public static <T> ApiResponse<T> error(int code, String errorCode, String msg) {
        return new ApiResponse<>(code, msg, null, errorCode, TraceContext.getTraceId());
    }

    public static <T> ApiResponse<T> error(int code, String errorCode, String msg, String traceId) {
        return new ApiResponse<>(code, msg, null, errorCode, traceId);
    }
}
