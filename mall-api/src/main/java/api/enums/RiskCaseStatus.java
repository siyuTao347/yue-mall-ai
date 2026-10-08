package api.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

public enum RiskCaseStatus {

    OPEN("OPEN"),
    PROCESSING("PROCESSING"),
    RESOLVED("RESOLVED"),
    CLOSED("CLOSED");

    private final String code;

    RiskCaseStatus(String code) {
        this.code = code;
    }

    @JsonValue
    public String getCode() {
        return code;
    }

    @JsonCreator
    public static RiskCaseStatus fromCode(String code) {
        RiskCaseStatus value = fromCodeOrNull(code);
        if (value == null) {
            throw new IllegalArgumentException("Invalid RiskCaseStatus code: " + code);
        }
        return value;
    }

    public static RiskCaseStatus fromCodeOrNull(String code) {
        if (code == null) {
            return null;
        }
        return Arrays.stream(values())
                .filter(value -> value.code.equals(code))
                .findFirst()
                .orElse(null);
    }

    public boolean canTransitionTo(RiskCaseStatus target) {
        if (target == null) {
            return false;
        }
        return switch (this) {
            case OPEN -> target == PROCESSING;
            case PROCESSING -> target == RESOLVED;
            case RESOLVED -> target == CLOSED;
            case CLOSED -> target == OPEN;
        };
    }
}
