package api.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

public enum EscrowStatus {

    NONE("NONE"),
    FROZEN("FROZEN"),
    SETTLE_PENDING("SETTLE_PENDING"),
    REFUND_PENDING("REFUND_PENDING"),
    SETTLED("SETTLED"),
    REFUNDED("REFUNDED");

    private final String code;

    EscrowStatus(String code) {
        this.code = code;
    }

    @JsonValue
    public String getCode() {
        return code;
    }

    @JsonCreator
    public static EscrowStatus fromCode(String code) {
        EscrowStatus value = fromCodeOrNull(code);
        if (value == null) {
            throw new IllegalArgumentException("Invalid EscrowStatus code: " + code);
        }
        return value;
    }

    public static EscrowStatus fromCodeOrNull(String code) {
        if (code == null) {
            return null;
        }
        return Arrays.stream(values())
                .filter(value -> value.code.equals(code))
                .findFirst()
                .orElse(null);
    }

    public boolean canTransitionTo(EscrowStatus target) {
        if (target == null) {
            return false;
        }
        return switch (this) {
            case NONE -> target == FROZEN;
            case FROZEN -> target == SETTLE_PENDING || target == REFUND_PENDING;
            case SETTLE_PENDING -> target == SETTLED;
            case REFUND_PENDING -> target == REFUNDED;
            case SETTLED, REFUNDED -> false;
        };
    }

    public boolean isTerminal() {
        return switch (this) {
            case SETTLED, REFUNDED -> true;
            default -> false;
        };
    }
}
