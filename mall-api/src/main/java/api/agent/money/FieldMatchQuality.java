package api.agent.money;

import java.io.Serializable;

/**
 * 渠道字段匹配质量枚举
 */
public enum FieldMatchQuality implements Serializable {
    MATCHED,
    MISMATCH,
    UNVERIFIED
}

