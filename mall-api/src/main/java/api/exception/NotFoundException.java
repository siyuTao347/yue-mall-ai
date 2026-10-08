package api.exception;

import api.response.ErrorCodes;

public class NotFoundException extends BizException {

    public NotFoundException(String message) {
        this(ErrorCodes.COMMON_NOT_FOUND, message);
    }

    public NotFoundException(String errorCode, String message) {
        super(404, errorCode, message);
    }
}
