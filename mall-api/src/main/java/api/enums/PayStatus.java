package api.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

public enum PayStatus {

    INIT("INIT"),
    PAYING("PAYING"),
    SUCCESS("SUCCESS"),
    FAILED("FAILED"),
    TIMEOUT("TIMEOUT"),
    LATE_SUCCESS("LATE_SUCCESS");

    private final String code;

    PayStatus(String code) {
        this.code = code;
    }

    @JsonValue
    public String getCode() {
        return code;
    }

    @JsonCreator
    public static PayStatus fromCode(String code) {
        PayStatus value = fromCodeOrNull(code);
        if (value == null) {
            throw new IllegalArgumentException("Invalid PayStatus code: " + code);
        }
        return value;
    }

    public static PayStatus fromCodeOrNull(String code) {
        if (code == null) {
            return null;
        }
        return Arrays.stream(values())
                .filter(value -> value.code.equals(code))
                .findFirst()
                .orElse(null);
    }

    public boolean canTransitionTo(PayStatus target) {
        if (target == null) {
            return false;
        }
        return switch (this) {
            case INIT -> target == PAYING || target == SUCCESS || target == FAILED;
            case PAYING -> target == SUCCESS || target == FAILED || target == TIMEOUT;
            case LATE_SUCCESS -> false;
            case SUCCESS, FAILED, TIMEOUT -> false;
        };
    }

    public boolean isTerminal() {
        return switch (this) {
            case SUCCESS, FAILED, TIMEOUT, LATE_SUCCESS -> true;
            default -> false;
        };
    }
}
