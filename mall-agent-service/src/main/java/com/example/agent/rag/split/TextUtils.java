package com.example.agent.rag.split;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 文本归一化、哈希与长度估算工具。切片必须是纯函数，所有处理都在此类中保持确定性。 */
public final class TextUtils {

    private TextUtils() {
    }

    /** 归一化正文：统一换行、去掉行尾空白、剥离 YAML front-matter、压缩超过 3 个的连续空行。 */
    public static String normalizeMarkdown(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String text = raw.replace("\r\n", "\n").replace("\r", "\n");
        text = stripFrontMatter(text);
        StringBuilder sb = new StringBuilder(text.length());
        int blankRun = 0;
        for (String line : text.split("\n", -1)) {
            String trimmed = stripTrailing(line);
            if (trimmed.isBlank()) {
                blankRun++;
                if (blankRun > 3) {
                    continue;
                }
            } else {
                blankRun = 0;
            }
            sb.append(trimmed).append('\n');
        }
        return sb.toString().strip();
    }

    /** 剥离文档开头的 YAML front-matter（--- ... ---），元数据由导入脚本单独处理。 */
    public static String stripFrontMatter(String text) {
        String trimmed = text.stripLeading();
        if (!trimmed.startsWith("---")) {
            return text;
        }
        int firstLineEnd = trimmed.indexOf('\n');
        if (firstLineEnd < 0) {
            return text;
        }
        int close = trimmed.indexOf("\n---", firstLineEnd);
        if (close < 0) {
            return text;
        }
        int afterClose = trimmed.indexOf('\n', close + 1);
        return afterClose < 0 ? "" : trimmed.substring(afterClose + 1);
    }

    private static String stripTrailing(String line) {
        int end = line.length();
        while (end > 0 && (line.charAt(end - 1) == ' ' || line.charAt(end - 1) == '\t')) {
            end--;
        }
        return line.substring(0, end);
    }

    public static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 估算 token 数：CJK 字符按 1 计，连续 ASCII 词按 1 计，其他字符按 0.25 计。 */
    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        double tokens = 0;
        boolean inAsciiWord = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isCjk(c)) {
                tokens += 1;
                inAsciiWord = false;
            } else if (isAsciiWordChar(c)) {
                if (!inAsciiWord) {
                    tokens += 1;
                    inAsciiWord = true;
                }
            } else {
                tokens += 0.25;
                inAsciiWord = false;
            }
        }
        return (int) Math.ceil(tokens);
    }

    public static boolean isCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3400 && c <= 0x4DBF)
                || (c >= 0xF900 && c <= 0xFAFF) || (c >= 0x3000 && c <= 0x303F);
    }

    public static boolean isAsciiWordChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                || c == '-' || c == '_' || c == '.';
    }

    /** 中文句末标点，用于句子级二次切分。 */
    public static boolean isSentenceBoundary(char c) {
        return c == '。' || c == '！' || c == '？' || c == '；' || c == '!' || c == '?' || c == ';';
    }
}
