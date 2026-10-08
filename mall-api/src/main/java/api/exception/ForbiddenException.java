package api.exception;

import api.response.ErrorCodes;

public class ForbiddenException extends BizException {

    public ForbiddenException(String message) {
        this(ErrorCodes.COMMON_FORBIDDEN, message);
    }

    public ForbiddenException(String errorCode, String message) {
        super(403, errorCode, message);
    }
}
