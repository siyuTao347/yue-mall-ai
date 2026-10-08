package api.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

public enum OrderStatus {

    CREATE_PENDING("CREATE_PENDING"),
    WAIT_PAY("WAIT_PAY"),
    PAY_CONFIRMING("PAY_CONFIRMING"),
    PAID("PAID"),
    DELIVERED("DELIVERED"),
    CONFIRMED("CONFIRMED"),
    SETTLING("SETTLING"),
    SETTLED("SETTLED"),
    REFUNDING("REFUNDING"),
    REFUNDED("REFUNDED"),
    CANCELLING("CANCELLING"),
    CANCELLED("CANCELLED"),
    CLOSED("CLOSED"),
    REJECTED("REJECTED");

    private final String code;

    OrderStatus(String code) {
        this.code = code;
    }

    @JsonValue
    public String getCode() {
        return code;
    }

    @JsonCreator
    public static OrderStatus fromCode(String code) {
        OrderStatus value = fromCodeOrNull(code);
        if (value == null) {
            throw new IllegalArgumentException("Invalid OrderStatus code: " + code);
        }
        return value;
    }

    public static OrderStatus fromCodeOrNull(String code) {
        if (code == null) {
            return null;
        }
        return Arrays.stream(values())
                .filter(value -> value.code.equals(code))
                .findFirst()
                .orElse(null);
    }

    public boolean canTransitionTo(OrderStatus target) {
        if (target == null) {
            return false;
        }
        return switch (this) {
            case CREATE_PENDING -> target == WAIT_PAY || target == CANCELLED;
            case WAIT_PAY -> target == PAY_CONFIRMING || target == PAID
                    || target == CANCELLING || target == CANCELLED;
            case PAY_CONFIRMING -> target == PAID || target == WAIT_PAY || target == CANCELLED;
            case PAID -> target == DELIVERED || target == REFUNDING || target == REFUNDED;
            case DELIVERED -> target == CONFIRMED || target == REFUNDING || target == REFUNDED;
            case CONFIRMED -> target == SETTLING || target == REFUNDING || target == REFUNDED;
            case SETTLING -> target == SETTLED || target == REFUNDING || target == REFUNDED;
            case REFUNDING -> target == REFUNDED;
            case CANCELLING -> target == CANCELLED || target == PAID;
            case SETTLED, REFUNDED, CANCELLED, CLOSED, REJECTED -> false;
        };
    }

    public boolean isTerminal() {
        return switch (this) {
            case SETTLED, REFUNDED, CANCELLED, CLOSED, REJECTED -> true;
            default -> false;
        };
    }
}
