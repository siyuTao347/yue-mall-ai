package api.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

public enum RiskScene {

    ORDER("ORDER"),
    PAYMENT("PAYMENT"),
    DELIVERY("DELIVERY"),
    CONFIRM("CONFIRM"),
    DISPUTE("DISPUTE"),
    LISTING("LISTING"),
    REGISTER("REGISTER"),
    LOGIN("LOGIN"),
    WITHDRAW("WITHDRAW"),
    MERCHANT("MERCHANT");

    private final String code;

    RiskScene(String code) {
        this.code = code;
    }

    @JsonValue
    public String getCode() {
        return code;
    }

    @JsonCreator
    public static RiskScene fromCode(String code) {
        RiskScene value = fromCodeOrNull(code);
        if (value == null) {
            throw new IllegalArgumentException("Invalid RiskScene code: " + code);
        }
        return value;
    }

    public static RiskScene fromCodeOrNull(String code) {
        if (code == null) {
            return null;
        }
        return Arrays.stream(values())
                .filter(value -> value.code.equals(code))
                .findFirst()
                .orElse(null);
    }
}
