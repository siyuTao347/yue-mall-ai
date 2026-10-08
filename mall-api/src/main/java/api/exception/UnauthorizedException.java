package api.exception;

import api.response.ErrorCodes;

public class UnauthorizedException extends BizException {

    public UnauthorizedException(String message) {
        this(ErrorCodes.COMMON_UNAUTHORIZED, message);
    }

    public UnauthorizedException(String errorCode, String message) {
        super(401, errorCode, message);
    }
}
