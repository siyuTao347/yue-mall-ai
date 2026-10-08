package com.example.agent.rag.split;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 父子分片器（small-to-big）。
 *
 * <p>父片（L1）按 Markdown 一/二级标题切分，超长再按三级标题或块体积二次切分；
 * 子片（L2）按段落聚合并带重叠，仅子片生成向量；检索命中子片后回溯父片作为上下文。</p>
 *
 * <p><b>纯函数约束</b>：同样的 {@link SplitRequest} 必须得到完全相同的输出，
 * 否则 chunk_hash 幂等与版本重建都会失效。</p>
 */
@Component
public class ParentChildSplitter {

    /** 父片最多与相邻父片合并的次数，避免把小节全部并成一片。 */
    private static final int MAX_MERGE_TIMES = 3;

    private final MarkdownBlockParser parser = new MarkdownBlockParser();

    public SplitResult split(SplitRequest request) {
        ChunkParams params = request.params();
        String normalized = TextUtils.normalizeMarkdown(request.content());
        if (normalized.isBlank()) {
            return new SplitResult("", List.of());
        }
        List<Section> sections = group(parser.parse(normalized));
        List<Section> sized = new ArrayList<>();
        for (Section section : sections) {
            sized.addAll(splitOversize(section, normalized, params));
        }
        List<Section> merged = mergeSmall(sized, params);

        List<ParentChildDraft> parents = new ArrayList<>(merged.size());
        int parentIndex = 0;
        for (Section section : merged) {
            parentIndex++;
            ChunkDraft parent = buildParent(section, normalized, request, parentIndex);
            List<ChunkDraft> children = buildChildren(section, normalized, request, parentIndex, params);
            parents.add(new ParentChildDraft(parent, children));
        }
        return new SplitResult(normalized, parents);
    }

    // ------------------------------------------------------------------ 一级切分

    /** 按一/二级标题分组；首个标题之前的内容归入「前言」。 */
    private List<Section> group(List<MarkdownBlock> blocks) {
        List<Section> sections = new ArrayList<>();
        Section current = null;
        String h1 = null;
        for (MarkdownBlock block : blocks) {
            if (block.type() == MarkdownBlock.BlockType.HEADING && block.headingLevel() == 1) {
                h1 = block.headingText();
            }
            boolean startsSection = block.type() == MarkdownBlock.BlockType.HEADING && block.headingLevel() <= 2;
            if (startsSection) {
                if (current != null && current.hasContent()) {
                    sections.add(current);
                }
                current = new Section();
                current.h1 = h1;
                current.h2 = block.headingLevel() == 2 ? block.headingText() : null;
                current.add(block);
            } else {
                if (current == null) {
                    current = new Section();
                    current.h1 = h1;
                }
                current.add(block);
            }
        }
        if (current != null && current.hasContent()) {
            sections.add(current);
        }
        return sections;
    }

    // ------------------------------------------------------------------ 父片二次切分

    private List<Section> splitOversize(Section section, String normalized, ChunkParams params) {
        if (section.length() <= params.parentMaxChars()) {
            return List.of(section);
        }
        List<Section> byHeading = splitByHeadingLevel(section, 3);
        List<Section> result = new ArrayList<>();
        for (Section part : byHeading) {
            result.addAll(splitBySize(part, normalized, params));
        }
        return result;
    }

    private List<Section> splitByHeadingLevel(Section section, int level) {
        List<Section> result = new ArrayList<>();
        Section current = null;
        for (MarkdownBlock block : section.blocks) {
            boolean splits = block.type() == MarkdownBlock.BlockType.HEADING && block.headingLevel() == level;
            if (splits) {
                if (current != null) {
                    result.add(current);
                }
                current = Section.inherit(section);
                current.h3 = block.headingText();
                current.add(block);
            } else if (current == null) {
                current = Section.inherit(section);
                current.add(block);
            } else {
                current.add(block);
            }
        }
        if (current != null) {
            result.add(current);
        }
        return result;
    }

    /** 仍超长时按块体积贪心切分，保证每片不超过 parentMaxChars（不可切分块豁免）。 */
    private List<Section> splitBySize(Section section, String normalized, ChunkParams params) {
        List<Section> result = new ArrayList<>();
        Section current = null;
        int currentLength = 0;
        for (MarkdownBlock block : section.blocks) {
            List<MarkdownBlock> parts = block.length() > params.parentMaxChars() && !block.atomic()
                    ? sentenceBlocks(block, normalized, params.parentMaxChars())
                    : List.of(block);
            for (MarkdownBlock part : parts) {
                if (current != null && currentLength >= params.parentTargetChars()) {
                    result.add(current);
                    current = null;
                    currentLength = 0;
                }
                if (current == null) {
                    current = Section.inherit(section);
                }
                current.add(part);
                currentLength += part.length();
            }
        }
        if (current != null) {
            result.add(current);
        }
        return result;
    }

    /**
     * 合并「无标题的片段」与「极短残片」到前一个父片。
     *
     * <p>刻意保守：只有当小节自身没有三级以内标题（前言/片段），或长度低于 minChars 的四分之一（残片）
     * 时才合并，并且要求前一个父片尚未达到目标长度、合并后不超上限。否则按段落切出的独立小节
     * 会被大量吞并，导致引用粒度退化为整篇文档。</p>
     */
    private List<Section> mergeSmall(List<Section> sections, ChunkParams params) {
        int stubThreshold = Math.max(60, params.parentMinChars() / 4);
        List<Section> result = new ArrayList<>();
        int mergeTimes = 0;
        for (Section section : sections) {
            Section previous = result.isEmpty() ? null : result.get(result.size() - 1);
            boolean noHeading = section.h2 == null && section.h3 == null;
            boolean tiny = section.length() < stubThreshold;
            boolean canMerge = previous != null && (noHeading || tiny)
                    && mergeTimes < MAX_MERGE_TIMES
                    && previous.length() < params.parentTargetChars()
                    && previous.length() + section.length() <= params.parentMaxChars();
            if (canMerge) {
                previous.blocks.addAll(section.blocks);
                previous.start = Math.min(previous.start, section.start);
                previous.end = Math.max(previous.end, section.end);
                mergeTimes++;
            } else {
                result.add(section);
                mergeTimes = 0;
            }
        }
        return result;
    }

    // ------------------------------------------------------------------ 父片 / 子片构建

    private ChunkDraft buildParent(Section section, String normalized, SplitRequest request, int parentIndex) {
        int[] range = trimRange(normalized, section.start, section.end);
        String content = normalized.substring(range[0], range[1]);
        String titlePath = section.titlePath(request.docTitle());
        String chunkNo = parentChunkNo(request, parentIndex);
        return new ChunkDraft(KnowledgeChunkLevel.PARENT, chunkNo,
                hash(request, 1, titlePath, content), titlePath, content,
                range[0], range[1], KeywordExtractor.extractJson(titlePath, content, request.params().keywordLimit()),
                false, TextUtils.estimateTokens(content));
    }

    private List<ChunkDraft> buildChildren(Section section, String normalized, SplitRequest request,
                                           int parentIndex, ChunkParams params) {
        int[] parentRange = trimRange(normalized, section.start, section.end);
        List<MarkdownBlock> units = contentBlocks(section);
        if (units.isEmpty()) {
            return List.of();
        }
        List<Unit> expanded = new ArrayList<>();
        for (MarkdownBlock block : units) {
            if (block.length() > params.childMaxChars() && !block.atomic()) {
                for (MarkdownBlock part : sentenceBlocks(block, normalized, params.childMaxChars())) {
                    expanded.add(new Unit(part.start(), part.end(), false, false, true));
                }
            } else {
                expanded.add(new Unit(block.start(), block.end(),
                        block.atomic(), block.type() == MarkdownBlock.BlockType.HEADING, false));
            }
        }

        List<Range> ranges = greedyRanges(expanded, params, parentRange);
        int maxPerParent = params.childMaxPerParent();
        if (ranges.size() > maxPerParent) {
            int regionLength = parentRange[1] - parentRange[0];
            int widerTarget = Math.max(params.childTargetChars(), regionLength / maxPerParent + 1);
            ChunkParams wider = new ChunkParams(params.parentTargetChars(), params.parentMaxChars(),
                    params.parentMinChars(), widerTarget, params.childMaxChars(), params.childMinChars(),
                    params.childOverlapChars(), maxPerParent, params.keywordLimit());
            ranges = greedyRanges(expanded, wider, parentRange);
        }

        List<ChunkDraft> children = new ArrayList<>(ranges.size());
        String titlePath = section.titlePath(request.docTitle());
        int childIndex = 0;
        for (Range range : ranges) {
            int[] trimmed = trimRange(normalized, range.start, range.end);
            if (trimmed[1] <= trimmed[0]) {
                continue;
            }
            String content = normalized.substring(trimmed[0], trimmed[1]);
            if (content.isBlank()) {
                continue;
            }
            childIndex++;
            String chunkNo = childChunkNo(request, parentIndex, childIndex);
            children.add(new ChunkDraft(KnowledgeChunkLevel.CHILD, chunkNo,
                    hash(request, 2, titlePath, content), titlePath, content,
                    trimmed[0], trimmed[1],
                    KeywordExtractor.extractJson(titlePath, content, request.params().keywordLimit()),
                    range.forced, TextUtils.estimateTokens(content)));
        }
        return children;
    }

    /** 子片正文使用的块：跳过小节开头的标题块（标题信息由 titlePath 承载）。 */
    private List<MarkdownBlock> contentBlocks(Section section) {
        int first = 0;
        while (first < section.blocks.size()
                && section.blocks.get(first).type() == MarkdownBlock.BlockType.HEADING) {
            first++;
        }
        return section.blocks.subList(first, section.blocks.size());
    }

    /** 贪心聚合子片区间：达到目标长度即断开，不在标题块后断开，不可切分块整体保留。 */
    private List<Range> greedyRanges(List<Unit> units, ChunkParams params, int[] parentRange) {
        List<Range> ranges = new ArrayList<>();
        Range current = null;
        boolean lastIsHeading = false;
        for (Unit unit : units) {
            int unitLength = unit.end() - unit.start();
            if (current == null) {
                current = new Range(unit.start(), unit.end(), unit.forced());
                lastIsHeading = unit.heading();
                continue;
            }
            int length = current.length();
            boolean reachTarget = length >= params.childTargetChars();
            boolean overMax = length + unitLength > params.childMaxChars() && length >= params.childMinChars();
            if ((reachTarget || overMax) && !lastIsHeading) {
                ranges.add(current);
                current = new Range(unit.start(), unit.end(), unit.forced());
            } else {
                current.end = unit.end();
                current.forced = current.forced || unit.forced();
            }
            lastIsHeading = unit.heading();
        }
        if (current != null) {
            ranges.add(current);
        }

        applyOverlap(ranges, params, parentRange);
        mergeTinyRanges(ranges, params, parentRange);
        return ranges;
    }

    /** 子片区间，携带硬切分标记。 */
    private static final class Range {
        private int start;
        private int end;
        private boolean forced;

        Range(int start, int end, boolean forced) {
            this.start = start;
            this.end = end;
            this.forced = forced;
        }

        int length() {
            return end - start;
        }
    }

    /** 子片重叠：从上一片尾部取 overlapChars 前缀，保持语义连续。 */
    private void applyOverlap(List<Range> ranges, ChunkParams params, int[] parentRange) {
        int overlap = params.childOverlapChars();
        if (overlap <= 0) {
            return;
        }
        for (int i = 1; i < ranges.size(); i++) {
            int candidate = ranges.get(i).start - overlap;
            int floor = Math.max(parentRange[0], ranges.get(i - 1).start + 1);
            ranges.get(i).start = Math.max(candidate, floor);
        }
    }

    /** 过短子片并入上一片；只有一片时保留原样。 */
    private void mergeTinyRanges(List<Range> ranges, ChunkParams params, int[] parentRange) {
        for (int i = ranges.size() - 1; i >= 1; i--) {
            Range range = ranges.get(i);
            if (range.length() < params.childMinChars()) {
                Range previous = ranges.get(i - 1);
                previous.end = Math.max(previous.end, range.end);
                previous.forced = previous.forced || range.forced;
                ranges.remove(i);
            }
        }
    }

    // ------------------------------------------------------------------ 工具方法

    private List<MarkdownBlock> sentenceBlocks(MarkdownBlock block, String normalized, int maxChars) {
        List<MarkdownBlock> parts = new ArrayList<>();
        int segmentStart = block.start();
        int lastBoundary = -1;
        for (int i = block.start(); i < block.end(); i++) {
            char c = normalized.charAt(i);
            if (TextUtils.isSentenceBoundary(c)) {
                lastBoundary = i + 1;
            }
            if (i - segmentStart + 1 >= maxChars) {
                int cut = lastBoundary > segmentStart ? lastBoundary : i + 1;
                parts.add(new MarkdownBlock(MarkdownBlock.BlockType.PARAGRAPH, segmentStart, cut, 0, null, false));
                segmentStart = cut;
                lastBoundary = -1;
            }
        }
        if (segmentStart < block.end()) {
            parts.add(new MarkdownBlock(MarkdownBlock.BlockType.PARAGRAPH, segmentStart, block.end(), 0, null, false));
        }
        return parts;
    }

    /** 去掉区间首尾空白，返回新的 [start, end)。 */
    static int[] trimRange(String text, int start, int end) {
        int s = Math.max(0, start);
        int e = Math.min(text.length(), end);
        while (s < e && Character.isWhitespace(text.charAt(s))) {
            s++;
        }
        while (e > s && Character.isWhitespace(text.charAt(e - 1))) {
            e--;
        }
        return new int[]{s, e};
    }

    private String hash(SplitRequest request, int level, String titlePath, String content) {
        return TextUtils.sha256Hex(request.docNo() + "|" + request.docVersion() + "|" + level + "|"
                + titlePath + "|" + content);
    }

    private String parentChunkNo(SplitRequest request, int parentIndex) {
        return "%s-v%d-P%03d".formatted(request.docNo(), request.docVersion(), parentIndex);
    }

    private String childChunkNo(SplitRequest request, int parentIndex, int childIndex) {
        return "%s-v%d-P%03d-C%02d".formatted(request.docNo(), request.docVersion(), parentIndex, childIndex);
    }

    /** 子片区间单元。 */
    private record Unit(int start, int end, boolean atomic, boolean heading, boolean forced) {
    }

    /** 切片过程中的小节。 */
    private static final class Section {
        private String h1;
        private String h2;
        private String h3;
        private final List<MarkdownBlock> blocks = new ArrayList<>();
        private int start = Integer.MAX_VALUE;
        private int end = 0;

        static Section inherit(Section source) {
            Section section = new Section();
            section.h1 = source.h1;
            section.h2 = source.h2;
            section.h3 = source.h3;
            return section;
        }

        void add(MarkdownBlock block) {
            blocks.add(block);
            start = Math.min(start, block.start());
            end = Math.max(end, block.end());
        }

        boolean hasContent() {
            // 解析器只产出非空块，因此存在非标题块即视为有正文
            return blocks.stream().anyMatch(block -> block.type() != MarkdownBlock.BlockType.HEADING);
        }

        int length() {
            return Math.max(0, end - start);
        }

        String titlePath(String docTitle) {
            String leaf;
            if (h2 != null) {
                leaf = h1 != null ? h1 + "/" + h2 : h2;
            } else if (h1 != null) {
                leaf = h1;
            } else {
                leaf = docTitle == null || docTitle.isBlank() ? "前言" : docTitle + "/前言";
            }
            if (h3 != null) {
                leaf = leaf + "/" + h3;
            }
            return leaf;
        }
    }

    /** 分片层级常量。 */
    private static final class KnowledgeChunkLevel {
        private static final int PARENT = 1;
        private static final int CHILD = 2;

        private KnowledgeChunkLevel() {
        }
    }
}
