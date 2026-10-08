package api.exception;

import api.response.ErrorCodes;

public class StateConflictException extends BizException {

    public StateConflictException(String message) {
        this(ErrorCodes.COMMON_STATE_CONFLICT, message);
    }

    public StateConflictException(String errorCode, String message) {
        super(409, errorCode, message);
    }
}
