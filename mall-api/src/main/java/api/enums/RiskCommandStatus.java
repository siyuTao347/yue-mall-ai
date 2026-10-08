package api.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

public enum RiskCommandStatus {

    NONE("NONE"),
    PENDING_SEND("PENDING_SEND"),
    SENT("SENT"),
    SUCCESS("SUCCESS"),
    FAILED("FAILED"),
    COMMAND_FAILED("COMMAND_FAILED"),
    DEAD_LETTER("DEAD_LETTER");

    private final String code;

    RiskCommandStatus(String code) {
        this.code = code;
    }

    @JsonValue
    public String getCode() {
        return code;
    }

    @JsonCreator
    public static RiskCommandStatus fromCode(String code) {
        RiskCommandStatus value = fromCodeOrNull(code);
        if (value == null) {
            throw new IllegalArgumentException("Invalid RiskCommandStatus code: " + code);
        }
        return value;
    }

    public static RiskCommandStatus fromCodeOrNull(String code) {
        if (code == null) {
            return null;
        }
        return Arrays.stream(values())
                .filter(value -> value.code.equals(code))
                .findFirst()
                .orElse(null);
    }

    public boolean canTransitionTo(RiskCommandStatus target) {
        if (target == null) {
            return false;
        }
        return switch (this) {
            case NONE -> target == PENDING_SEND;
            case PENDING_SEND -> target == SENT || target == FAILED || target == COMMAND_FAILED;
            case SENT -> target == SUCCESS || target == COMMAND_FAILED;
            case FAILED -> target == PENDING_SEND;
            case COMMAND_FAILED -> target == PENDING_SEND || target == SUCCESS || target == DEAD_LETTER;
            case SUCCESS, DEAD_LETTER -> false;
        };
    }

    public boolean isTerminal() {
        return switch (this) {
            case SUCCESS, DEAD_LETTER -> true;
            default -> false;
        };
    }
}
