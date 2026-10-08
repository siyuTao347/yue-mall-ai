package api.exception;

import api.response.ErrorCodes;

import java.util.Map;

/**
 * 远程依赖失败：包含下游服务与幂等键，便于排障与重试。
 */
public class RemoteDependencyException extends BizException {

    public RemoteDependencyException(String message) {
        this(ErrorCodes.COMMON_REMOTE_DEPENDENCY_ERROR, message, Map.of());
    }

    public RemoteDependencyException(String message, Map<String, Object> context) {
        this(ErrorCodes.COMMON_REMOTE_DEPENDENCY_ERROR, message, context);
    }

    public RemoteDependencyException(String errorCode, String message, Map<String, Object> context) {
        super(502, errorCode, message, context);
    }

    public String downstream() {
        Object value = getContext().get("downstream");
        return value == null ? null : String.valueOf(value);
    }

    public String idempotencyKey() {
        Object value = getContext().get("idempotencyKey");
        return value == null ? null : String.valueOf(value);
    }
}
