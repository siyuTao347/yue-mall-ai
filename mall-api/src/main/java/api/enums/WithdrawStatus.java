package api.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

public enum WithdrawStatus {

    SUBMITTED("SUBMITTED"),
    MANUAL_REVIEW("MANUAL_REVIEW"),
    FROZEN("FROZEN"),
    APPROVED("APPROVED"),
    PAYOUT_SUCCESS("PAYOUT_SUCCESS"),
    REJECTED("REJECTED");

    private final String code;

    WithdrawStatus(String code) {
        this.code = code;
    }

    @JsonValue
    public String getCode() {
        return code;
    }

    @JsonCreator
    public static WithdrawStatus fromCode(String code) {
        WithdrawStatus value = fromCodeOrNull(code);
        if (value == null) {
            throw new IllegalArgumentException("Invalid WithdrawStatus code: " + code);
        }
        return value;
    }

    public static WithdrawStatus fromCodeOrNull(String code) {
        if (code == null) {
            return null;
        }
        return Arrays.stream(values())
                .filter(value -> value.code.equals(code))
                .findFirst()
                .orElse(null);
    }

    public boolean canTransitionTo(WithdrawStatus target) {
        if (target == null) {
            return false;
        }
        return switch (this) {
            case SUBMITTED -> target == APPROVED || target == REJECTED
                    || target == MANUAL_REVIEW || target == FROZEN;
            case MANUAL_REVIEW, FROZEN -> target == APPROVED || target == REJECTED;
            case APPROVED -> target == PAYOUT_SUCCESS || target == REJECTED;
            case PAYOUT_SUCCESS, REJECTED -> false;
        };
    }

    public boolean isTerminal() {
        return switch (this) {
            case PAYOUT_SUCCESS, REJECTED -> true;
            default -> false;
        };
    }
}
