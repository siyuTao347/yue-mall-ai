package com.example.agent.rag.split;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextUtilsTest {

    @Test
    @DisplayName("剥离 YAML front-matter 后只保留正文")
    void stripFrontMatter() {
        String raw = """
                ---
                doc_id: PROD-001
                title: "AK-47 | 红线"
                ---

                ## 1. 文档控制

                正文内容
                """;
        String normalized = TextUtils.normalizeMarkdown(raw);
        assertFalse(normalized.contains("doc_id"), "front-matter 应被剥离");
        assertTrue(normalized.startsWith("## 1. 文档控制"));
    }

    @Test
    @DisplayName("归一化统一换行并压缩多余空行")
    void normalizeLineEndings() {
        // 连续 5 个空行被压缩为 3 个（即 4 个换行符）
        String normalized = TextUtils.normalizeMarkdown("a\r\n\r\n\r\n\r\n\r\nb");
        assertEquals("a\n\n\n\nb", normalized);
    }

    @Test
    @DisplayName("同内容哈希一致，改一字哈希变化")
    void hashIsStable() {
        String first = TextUtils.sha256Hex("AK-47 | 红线");
        assertEquals(first, TextUtils.sha256Hex("AK-47 | 红线"));
        assertNotEquals(first, TextUtils.sha256Hex("AK-47 | 红線"));
        assertEquals(64, first.length());
    }

    @Test
    @DisplayName("token 估算随文本增长而增长")
    void estimateTokens() {
        int shortTokens = TextUtils.estimateTokens("AK-47 | 红线");
        int longTokens = TextUtils.estimateTokens("AK-47 | 红线".repeat(50));
        assertTrue(shortTokens > 0);
        assertTrue(longTokens > shortTokens);
    }

    @Test
    @DisplayName("空文档归一化为空串")
    void blankDocument() {
        assertEquals("", TextUtils.normalizeMarkdown(null));
        assertEquals("", TextUtils.normalizeMarkdown("   \n  "));
    }
}
