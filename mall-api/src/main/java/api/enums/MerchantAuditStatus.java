package api.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

public enum MerchantAuditStatus {

    PENDING("PENDING"),
    APPROVED("APPROVED"),
    REJECTED("REJECTED");

    private final String code;

    MerchantAuditStatus(String code) {
        this.code = code;
    }

    @JsonValue
    public String getCode() {
        return code;
    }

    @JsonCreator
    public static MerchantAuditStatus fromCode(String code) {
        MerchantAuditStatus value = fromCodeOrNull(code);
        if (value == null) {
            throw new IllegalArgumentException("Invalid MerchantAuditStatus code: " + code);
        }
        return value;
    }

    public static MerchantAuditStatus fromCodeOrNull(String code) {
        if (code == null) {
            return null;
        }
        return Arrays.stream(values())
                .filter(value -> value.code.equals(code))
                .findFirst()
                .orElse(null);
    }

    public boolean canTransitionTo(MerchantAuditStatus target) {
        if (target == null) {
            return false;
        }
        return switch (this) {
            case PENDING -> target == APPROVED || target == REJECTED;
            case APPROVED, REJECTED -> false;
        };
    }

    public boolean isTerminal() {
        return switch (this) {
            case APPROVED, REJECTED -> true;
            default -> false;
        };
    }
}
