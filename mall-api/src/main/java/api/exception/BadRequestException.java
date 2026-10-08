package api.exception;

import api.response.ErrorCodes;

public class BadRequestException extends BizException {

    public BadRequestException(String message) {
        this(ErrorCodes.COMMON_REQUEST_PARAM_INVALID, message);
    }

    public BadRequestException(String errorCode, String message) {
        super(400, errorCode, message);
    }
}
