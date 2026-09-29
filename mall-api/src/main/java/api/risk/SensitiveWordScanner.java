package api.risk;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SensitiveWordScanner {
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private static final Pattern WE_CHAT = Pattern.compile("(?:微信|weixin|vx|v信)[a-zA-Z][a-zA-Z0-9_-]{5,19}");
    private static final Pattern QQ = Pattern.compile("(?:qq|扣扣)[1-9][0-9]{4,10}");
    private static final Pattern TELEGRAM = Pattern.compile("(?:telegram|tg|电报)[a-zA-Z0-9_]{4,32}");

    private SensitiveWordScanner() {
    }

    public static List<SensitiveWordDTO> defaultWords() {
        return List.of(
                word("ILLEGAL_VIRTUAL_COIN", "ILLEGAL_ASSET", "虚拟货币"),
                word("ILLEGAL_RECHARGE", "ILLEGAL_ASSET", "代充"),
                word("ILLEGAL_CHEAT", "ILLEGAL_ASSET", "外挂"),
                word("ILLEGAL_STOLEN_ACCOUNT", "ILLEGAL_ASSET", "盗号账号"),
                word("OFF_WECHAT", "OFF_PLATFORM_CONTACT", "微信"),
                word("OFF_QQ", "OFF_PLATFORM_CONTACT", "qq"),
                word("OFF_TELEGRAM", "OFF_PLATFORM_CONTACT", "telegram"),
                word("PRIVATE_OFFLINE", "PRIVATE_TRANSACTION", "私下交易"),
                word("PRIVATE_BYPASS", "PRIVATE_TRANSACTION", "绕过平台"),
                word("PRIVATE_PLATFORM_GUARANTEE", "PRIVATE_TRANSACTION", "平台外担保"),
                word("RECOVERY_ACCOUNT", "ACCOUNT_RECOVERY", "找回账号"),
                word("RECOVERY_EMAIL", "ACCOUNT_RECOVERY", "原始邮箱"),
                word("RECOVERY_ID_CHANGE", "ACCOUNT_RECOVERY", "身份证可改"),
                word("FRAUD_STABLE_PROFIT", "FRAUD_HINT", "稳赚"),
                word("FRAUD_GUARANTEE_PASS", "FRAUD_HINT", "包过"),
                word("FRAUD_MONEY_LAUNDERING", "FRAUD_HINT", "洗黑产"),
                word("FRAUD_COLLECT_MONEY", "FRAUD_HINT", "代收")
        );
    }

    public static List<SensitiveWordHitDTO> scan(String content, List<SensitiveWordDTO> words) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        String normalized = normalize(content);
        Map<String, SensitiveWordHitDTO> hits = new LinkedHashMap<>();
        for (SensitiveWordDTO item : words == null ? defaultWords() : words) {
            if (item == null || item.getWord() == null || !normalized.contains(normalize(item.getWord()))) {
                continue;
            }
            put(hits, item.getCategory(), item.getWordCode(), content);
        }
        putIfMatches(hits, PHONE.matcher(normalized), "BUILTIN_PHONE", "OFF_PLATFORM_CONTACT", content);
        putIfMatches(hits, WE_CHAT.matcher(normalized), "BUILTIN_WECHAT", "OFF_PLATFORM_CONTACT", content);
        putIfMatches(hits, QQ.matcher(normalized), "BUILTIN_QQ", "OFF_PLATFORM_CONTACT", content);
        putIfMatches(hits, TELEGRAM.matcher(normalized), "BUILTIN_TELEGRAM", "OFF_PLATFORM_CONTACT", content);
        return List.copyOf(hits.values());
    }

    public static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .replaceAll("[\\s\\p{Punct}，。！？；：“”‘’（）《》、]+", "")
                .toLowerCase(Locale.ROOT);
    }

    private static void putIfMatches(Map<String, SensitiveWordHitDTO> hits, Matcher matcher,
                                     String wordCode, String category, String content) {
        if (matcher.find()) {
            put(hits, category, wordCode, content);
        }
    }

    private static void put(Map<String, SensitiveWordHitDTO> hits, String category,
                            String wordCode, String content) {
        hits.putIfAbsent(category + ":" + wordCode, SensitiveWordHitDTO.builder()
                .category(category)
                .wordCode(wordCode)
                .contentHash(RiskSupport.sha256(content))
                .contentLength(content.length())
                .build());
    }

    private static SensitiveWordDTO word(String code, String category, String text) {
        return SensitiveWordDTO.builder().wordCode(code).category(category).word(text).build();
    }
}
