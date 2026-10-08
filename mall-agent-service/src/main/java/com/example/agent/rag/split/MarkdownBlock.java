package com.example.agent.rag.split;

/**
 * Markdown 文档解析出的最小块。
 *
 * @param type       块类型
 * @param start       在归一化正文中的起始偏移（含）
 * @param end         在归一化正文中的结束偏移（不含）
 * @param headingLevel 标题层级，非标题为 0
 * @param headingText  标题文本（不含 #），非标题为 null
 * @param atomic       是否为不可切分块（代码块、表格）
 */
public record MarkdownBlock(BlockType type, int start, int end, int headingLevel,
                            String headingText, boolean atomic) {

    public enum BlockType {
        HEADING,
        PARAGRAPH,
        CODE,
        TABLE
    }

    public String text(String normalized) {
        return normalized.substring(start, end);
    }

    public int length() {
        return end - start;
    }
}
