package api.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

public enum DeliveryStatus {

    PENDING("PENDING"),
    DELIVERED("DELIVERED"),
    VIEWED("VIEWED"),
    CONFIRMED("CONFIRMED");

    private final String code;

    DeliveryStatus(String code) {
        this.code = code;
    }

    @JsonValue
    public String getCode() {
        return code;
    }

    @JsonCreator
    public static DeliveryStatus fromCode(String code) {
        DeliveryStatus value = fromCodeOrNull(code);
        if (value == null) {
            throw new IllegalArgumentException("Invalid DeliveryStatus code: " + code);
        }
        return value;
    }

    public static DeliveryStatus fromCodeOrNull(String code) {
        if (code == null) {
            return null;
        }
        return Arrays.stream(values())
                .filter(value -> value.code.equals(code))
                .findFirst()
                .orElse(null);
    }

    public boolean canTransitionTo(DeliveryStatus target) {
        if (target == null) {
            return false;
        }
        return switch (this) {
            case PENDING -> target == DELIVERED;
            case DELIVERED -> target == VIEWED || target == CONFIRMED;
            case VIEWED -> target == CONFIRMED;
            case CONFIRMED -> false;
        };
    }

    public boolean isTerminal() {
        return switch (this) {
            case CONFIRMED -> true;
            default -> false;
        };
    }
}
