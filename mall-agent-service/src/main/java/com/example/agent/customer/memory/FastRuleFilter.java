package com.example.agent.customer.memory;

import java.util.regex.Pattern;

/**
 * Fast rule-based pre-filter to determine whether a user message potentially contains long-term purchase preferences.
 * If there are no habitual or preference keywords, the extraction worker skips the expensive LLM invocation immediately.
 */
public final class FastRuleFilter {
    private static final Pattern PREFERENCE_SIGNALS = Pattern.compile(
        "(?s).*(平时|一般|通常|习惯|更喜欢|更偏好|偏好|只选|只买|不买|不要|别给我|以后|月卡|预算|自动发货|人工发货|只考虑|不考虑|尽量|控在|控制在|以内|以下|区间).*");

    private static final Pattern TEMPORARY_OR_INSTRUCTION_ONLY = Pattern.compile(
        "(?s)^(?:你好|您好|在吗|什么时候发货|查一下物流|发货了吗|催单|我要退款|退货|申请售后|好的|行|可以|谢谢|不用了|再见)[!！?？。.,，、～~\\s]*$");

    private FastRuleFilter() {}

    public static boolean mayContainPreference(String text) {
        if (text == null || text.isBlank()) return false;
        String q = text.strip();
        if (TEMPORARY_OR_INSTRUCTION_ONLY.matcher(q).matches()) {
            return false;
        }
        return PREFERENCE_SIGNALS.matcher(q).matches();
    }
}
