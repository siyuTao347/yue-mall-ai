package api.agent.money;

/**
 * 关联质量
 */
public enum MoneyLinkQuality {
    /** 精确关联（如精确外键或明确幂等键映射） */
    EXACT,
    /** 受控规则映射（如已验证的业务前缀规则） */
    MAPPED,
    /** 历史无法确切关联（不通过猜测补齐） */
    LEGACY_UNLINKED,
    /** 存在多重冲突或不一致 */
    CONFLICT
}

