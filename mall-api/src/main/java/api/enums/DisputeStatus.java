package api.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

public enum DisputeStatus {

    NONE("NONE"),
    OPEN("OPEN"),
    NEGOTIATING("NEGOTIATING"),
    ARBITRATING("ARBITRATING"),
    APPEALED("APPEALED"),
    RESOLVED("RESOLVED");

    private final String code;

    DisputeStatus(String code) {
        this.code = code;
    }

    @JsonValue
    public String getCode() {
        return code;
    }

    @JsonCreator
    public static DisputeStatus fromCode(String code) {
        DisputeStatus value = fromCodeOrNull(code);
        if (value == null) {
            throw new IllegalArgumentException("Invalid DisputeStatus code: " + code);
        }
        return value;
    }

    public static DisputeStatus fromCodeOrNull(String code) {
        if (code == null) {
            return null;
        }
        return Arrays.stream(values())
                .filter(value -> value.code.equals(code))
                .findFirst()
                .orElse(null);
    }

    public boolean canTransitionTo(DisputeStatus target) {
        if (target == null) {
            return false;
        }
        return switch (this) {
            case NONE -> target == OPEN;
            case OPEN -> target == NEGOTIATING || target == ARBITRATING || target == RESOLVED;
            case NEGOTIATING -> target == ARBITRATING || target == RESOLVED;
            case ARBITRATING -> target == APPEALED || target == RESOLVED;
            case APPEALED -> target == ARBITRATING || target == RESOLVED;
            case RESOLVED -> false;
        };
    }

    public boolean isTerminal() {
        return switch (this) {
            case RESOLVED -> true;
            default -> false;
        };
    }
}
