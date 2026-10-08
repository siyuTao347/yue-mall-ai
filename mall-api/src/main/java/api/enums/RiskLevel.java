package api.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

/**
 * 风险等级：数字越大风险越高，禁止依赖 ordinal。
 */
public enum RiskLevel {
    LOW("LOW", 1),
    MEDIUM("MEDIUM", 2),
    HIGH("HIGH", 3),
    CRITICAL("CRITICAL", 4);

    private final String code;
    private final int rank;

    RiskLevel(String code, int rank) {
        this.code = code;
        this.rank = rank;
    }

    @JsonValue
    public String getCode() {
        return code;
    }

    public int getRank() {
        return rank;
    }

    public boolean atLeast(RiskLevel other) {
        return other != null && this.rank >= other.rank;
    }

    @JsonCreator
    public static RiskLevel fromCode(String code) {
        RiskLevel value = fromCodeOrNull(code);
        if (value == null) {
            throw new IllegalArgumentException("Invalid RiskLevel code: " + code);
        }
        return value;
    }

    public static RiskLevel fromCodeOrNull(String code) {
        if (code == null) {
            return null;
        }
        return Arrays.stream(values())
                .filter(value -> value.code.equals(code))
                .findFirst()
                .orElse(null);
    }
}
