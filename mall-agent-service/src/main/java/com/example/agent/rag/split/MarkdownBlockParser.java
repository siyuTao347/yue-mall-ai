package com.example.agent.rag.split;

import java.util.ArrayList;
import java.util.List;

/**
 * Markdown 块解析器：把正文切成标题 / 段落 / 代码块 / 表格四类块。
 * <p>代码块与表格标记为不可切分块，切片时整体保留，避免破坏语义。</p>
 */
public final class MarkdownBlockParser {

    private static final int MAX_HEADING_LEVEL = 6;

    public List<MarkdownBlock> parse(String normalized) {
        List<MarkdownBlock> blocks = new ArrayList<>();
        if (normalized == null || normalized.isEmpty()) {
            return blocks;
        }
        String[] lines = normalized.split("\n", -1);
        int[] starts = new int[lines.length + 1];
        int acc = 0;
        for (int i = 0; i < lines.length; i++) {
            starts[i] = acc;
            acc += lines[i].length() + 1;
        }
        starts[lines.length] = acc;

        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            if (line.isBlank()) {
                i++;
                continue;
            }
            if (isFenceStart(line)) {
                int j = i + 1;
                while (j < lines.length && !isFenceStart(lines[j])) {
                    j++;
                }
                int last = Math.min(j, lines.length - 1);
                blocks.add(build(MarkdownBlock.BlockType.CODE, lines, starts, i, last, true));
                i = last + 1;
                continue;
            }
            if (isTableRow(line)) {
                int j = i;
                while (j + 1 < lines.length && isTableRow(lines[j + 1])) {
                    j++;
                }
                blocks.add(build(MarkdownBlock.BlockType.TABLE, lines, starts, i, j, true));
                i = j + 1;
                continue;
            }
            int level = headingLevel(line);
            if (level > 0) {
                int end = starts[i] + line.length();
                blocks.add(new MarkdownBlock(MarkdownBlock.BlockType.HEADING, starts[i], end, level,
                        line.substring(level).strip(), false));
                i++;
                continue;
            }
            int j = i;
            while (j + 1 < lines.length && !lines[j + 1].isBlank()
                    && headingLevel(lines[j + 1]) == 0
                    && !isFenceStart(lines[j + 1])
                    && !isTableRow(lines[j + 1])) {
                j++;
            }
            blocks.add(build(MarkdownBlock.BlockType.PARAGRAPH, lines, starts, i, j, false));
            i = j + 1;
        }
        return blocks;
    }

    private MarkdownBlock build(MarkdownBlock.BlockType type, String[] lines, int[] starts,
                                int from, int to, boolean atomic) {
        int start = starts[from];
        int end = starts[to] + lines[to].length();
        return new MarkdownBlock(type, start, end, 0, null, atomic);
    }

    private boolean isFenceStart(String line) {
        String trimmed = line.stripLeading();
        return trimmed.startsWith("```") || trimmed.startsWith("~~~");
    }

    private boolean isTableRow(String line) {
        String trimmed = line.strip();
        return trimmed.startsWith("|") && trimmed.endsWith("|") && trimmed.length() > 1;
    }

    /** 解析标题层级；非标题返回 0。 */
    public int headingLevel(String line) {
        String trimmed = line.stripLeading();
        int level = 0;
        while (level < trimmed.length() && trimmed.charAt(level) == '#' && level < MAX_HEADING_LEVEL) {
            level++;
        }
        if (level == 0 || level >= trimmed.length()) {
            return 0;
        }
        return trimmed.charAt(level) == ' ' ? level : 0;
    }
}
