package com.example.agent.rag.embed;

/**
 * 向量化输入拼装：子片正文必须带上标题路径，否则「商品标价 1288.00」这类片段会丢失主语。
 * <p>格式：{@code 【类型】标题路径\n子片正文}。</p>
 */
public final class EmbeddingTextBuilder {

    private EmbeddingTextBuilder() {
    }

    public static String build(String docType, String titlePath, String content, int maxChars) {
        String text = "【" + label(docType) + "】" + (titlePath == null ? "" : titlePath) + "\n"
                + (content == null ? "" : content);
        if (maxChars > 0 && text.length() > maxChars) {
            return text.substring(0, maxChars);
        }
        return text;
    }

    public static String label(String docType) {
        if (docType == null) {
            return "知识文档";
        }
        return switch (docType.toUpperCase()) {
            case "PRODUCT" -> "商品介绍";
            case "RULE" -> "平台规则";
            case "POLICY" -> "平台政策";
            case "AFTER_SALE" -> "售后规则";
            case "RISK" -> "风控规则";
            case "FAQ" -> "常见问题";
            default -> "知识文档";
        };
    }
}
