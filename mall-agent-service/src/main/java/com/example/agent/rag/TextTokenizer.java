package com.example.agent.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.example.agent.rag.split.TextUtils;

/**
 * 轻量分词：中文按 2-gram，英文/型号/数字按词。
 * <p>同时服务于关键词提取（降级检索）与 Mock 向量，保证两条路径的分词口径一致。</p>
 */
public final class TextTokenizer {

    private TextTokenizer() {
    }

    public static List<String> tokens(String text) {
        List<String> tokens = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return tokens;
        }
        StringBuilder ascii = new StringBuilder();
        StringBuilder cjk = new StringBuilder();
        for (int i = 0; i <= text.length(); i++) {
            char c = i < text.length() ? text.charAt(i) : ' ';
            if (TextUtils.isCjk(c)) {
                flushAscii(ascii, tokens);
                cjk.append(c);
            } else if (TextUtils.isAsciiWordChar(c)) {
                flushCjk(cjk, tokens);
                ascii.append(c);
            } else {
                flushAscii(ascii, tokens);
                flushCjk(cjk, tokens);
            }
        }
        return tokens;
    }

    private static void flushAscii(StringBuilder buffer, List<String> tokens) {
        if (buffer.isEmpty()) {
            return;
        }
        String word = buffer.toString().toLowerCase(Locale.ROOT);
        buffer.setLength(0);
        if (word.length() >= 2 || word.chars().anyMatch(Character::isDigit)) {
            tokens.add(word);
        }
    }

    private static void flushCjk(StringBuilder buffer, List<String> tokens) {
        if (buffer.isEmpty()) {
            return;
        }
        String run = buffer.toString();
        buffer.setLength(0);
        if (run.length() == 1) {
            tokens.add(run);
            return;
        }
        for (int i = 0; i + 1 < run.length(); i++) {
            tokens.add(run.substring(i, i + 2));
        }
    }
}
