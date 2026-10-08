package api.exception;

import api.response.ErrorCodes;

public class ValidationException extends BizException {

    public ValidationException(String message) {
        this(ErrorCodes.COMMON_REQUEST_PARAM_INVALID, message);
    }

    public ValidationException(String errorCode, String message) {
        super(400, errorCode, message);
    }
}
