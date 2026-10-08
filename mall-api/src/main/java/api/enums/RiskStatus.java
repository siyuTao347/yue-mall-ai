package api.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

public enum RiskStatus {

    NORMAL("NORMAL"),
    MANUAL_REVIEW("MANUAL_REVIEW"),
    FROZEN("FROZEN"),
    LIMITED("LIMITED"),
    REJECTED("REJECTED");

    private final String code;

    RiskStatus(String code) {
        this.code = code;
    }

    @JsonValue
    public String getCode() {
        return code;
    }

    @JsonCreator
    public static RiskStatus fromCode(String code) {
        RiskStatus value = fromCodeOrNull(code);
        if (value == null) {
            throw new IllegalArgumentException("Invalid RiskStatus code: " + code);
        }
        return value;
    }

    public static RiskStatus fromCodeOrNull(String code) {
        if (code == null) {
            return null;
        }
        return Arrays.stream(values())
                .filter(value -> value.code.equals(code))
                .findFirst()
                .orElse(null);
    }

    public boolean canTransitionTo(RiskStatus target) {
        if (target == null) {
            return false;
        }
        return switch (this) {
            case NORMAL -> target == MANUAL_REVIEW || target == FROZEN || target == LIMITED
                    || target == REJECTED;
            case MANUAL_REVIEW -> target == NORMAL || target == FROZEN || target == LIMITED
                    || target == REJECTED;
            case FROZEN, LIMITED -> target == NORMAL || target == REJECTED;
            case REJECTED -> false;
        };
    }

    public boolean isTerminal() {
        return switch (this) {
            case REJECTED -> true;
            default -> false;
        };
    }
}
