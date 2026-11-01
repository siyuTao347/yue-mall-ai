package com.example.agent.customer;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 客服槽位集中注册表
 * <p>
 * 集中定义 slotName、格式校验、实体集合名、显示名与槽位抽取规则。
 * 特别处理：在抽取独立订单号时，先屏蔽 PA_ / RF_ 等资金引用中的内嵌编号，避免误识别。
 * </p>
 */
public final class CustomerSlotRegistry {

    private CustomerSlotRegistry() {}

    public static final Pattern ORDER = Pattern.compile("(?i)(?<![a-z0-9_])(?:SO|TR|T)\\d{6,40}(?![a-z0-9_])");
    public static final Pattern DISPUTE = Pattern.compile("(?i)(?<![a-z0-9_])(?:DSP|DP|D)\\d{6,40}(?![a-z0-9_])");
    public static final Pattern ITEM_ID = Pattern.compile("(?i)(?:ITEM-|PROD-)(\\d{1,10})(?!\\d)|(?<=商品|编号|ID)[\\s:#：]*(\\d{1,10})(?!\\d)");
    public static final Pattern ATTEMPT_NO = Pattern.compile("(?i)PA_[A-Za-z0-9_]{6,64}");
    public static final Pattern REFUND_NO = Pattern.compile("(?i)(?:RF_[A-Za-z0-9_]{6,64}|REF_SUSPENSE_[A-Za-z0-9_]{6,64})");
    public static final Pattern MONEY_CASE_NO = Pattern.compile("(?i)MC[0-9a-fA-F]{30,32}");

    public static final Set<String> SUPPORTED_SLOTS = Set.of(
            "orderNo", "disputeNo", "itemId", "attemptNo", "refundNo", "moneyCaseNo"
    );

    public static Pattern pattern(String key) {
        if (key == null) return Pattern.compile("(?!)");
        return switch (key) {
            case "orderNo" -> ORDER;
            case "disputeNo" -> DISPUTE;
            case "itemId" -> ITEM_ID;
            case "attemptNo" -> ATTEMPT_NO;
            case "refundNo" -> REFUND_NO;
            case "moneyCaseNo" -> MONEY_CASE_NO;
            default -> Pattern.compile("(?!)");
        };
    }

    public static String collection(String key) {
        if (key == null) return "";
        return switch (key) {
            case "orderNo" -> "orders";
            case "disputeNo" -> "disputes";
            case "itemId" -> "items";
            case "moneyCaseNo" -> "moneyCases";
            default -> "";
        };
    }

    public static String displayName(String key) {
        if (key == null) return "";
        return switch (key) {
            case "orderNo" -> "订单号";
            case "disputeNo" -> "售后单号";
            case "itemId" -> "商品编号";
            case "attemptNo" -> "支付尝试编号";
            case "refundNo" -> "退款单号";
            case "moneyCaseNo" -> "资金案件编号";
            default -> key;
        };
    }

    public static String promptMissing(String key) {
        if (key == null) return "";
        return switch (key) {
            case "orderNo" -> "订单号";
            case "disputeNo" -> "售后单号";
            case "itemId" -> "商品编号";
            case "attemptNo" -> "支付尝试编号";
            case "refundNo" -> "退款单号";
            case "moneyCaseNo" -> "资金案件编号";
            default -> key;
        };
    }

    /**
     * 抽取文本中的所有已知槽位。
     * 为防止嵌入在 PA_ / RF_ 中的订单号（例如 PA_T202610310001_01）被误识别为独立订单号，
     * 在匹配 ORDER 之前先对资金片段进行掩码遮蔽。
     */
    public static Map<String, String> extractSlots(String text) {
        Map<String, String> result = new LinkedHashMap<>();
        if (text == null || text.isBlank()) {
            return result;
        }

        // 1. 抽取 moneyCaseNo
        Matcher mcMatcher = MONEY_CASE_NO.matcher(text);
        if (mcMatcher.find()) {
            result.put("moneyCaseNo", mcMatcher.group().toUpperCase(Locale.ROOT));
        }

        // 2. 抽取 attemptNo
        Matcher paMatcher = ATTEMPT_NO.matcher(text);
        if (paMatcher.find()) {
            result.put("attemptNo", paMatcher.group());
        }

        // 3. 抽取 refundNo
        Matcher rfMatcher = REFUND_NO.matcher(text);
        if (rfMatcher.find()) {
            result.put("refundNo", rfMatcher.group());
        }

        // 4. 抽取 disputeNo
        Matcher dpMatcher = DISPUTE.matcher(text);
        if (dpMatcher.find()) {
            result.put("disputeNo", dpMatcher.group().toUpperCase(Locale.ROOT));
        }

        // 5. 抽取 itemId
        Matcher itemMatcher = ITEM_ID.matcher(text);
        if (itemMatcher.find()) {
            String val = itemMatcher.group(1) != null ? itemMatcher.group(1) : itemMatcher.group(2);
            if (val != null && !val.isBlank()) {
                result.put("itemId", val);
            }
        }

        // 6. 抽取 orderNo：先将 PA_ / RF_ 掩蔽，再匹配独立 orderNo
        String masked = ATTEMPT_NO.matcher(text).replaceAll(m -> "_".repeat(m.group().length()));
        masked = REFUND_NO.matcher(masked).replaceAll(m -> "_".repeat(m.group().length()));
        Matcher orderMatcher = ORDER.matcher(masked);
        if (orderMatcher.find()) {
            result.put("orderNo", orderMatcher.group().toUpperCase(Locale.ROOT));
        }

        return result;
    }
}
