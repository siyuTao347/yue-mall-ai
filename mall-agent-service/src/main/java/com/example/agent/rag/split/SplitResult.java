package com.example.agent.rag.split;

import java.util.List;

/** 切片结果。{@code normalizedContent} 与草稿中的 charStart/charEnd 使用同一坐标系。 */
public record SplitResult(String normalizedContent, List<ParentChildDraft> parents) {

    public int parentCount() {
        return parents.size();
    }

    public int childCount() {
        return parents.stream().mapToInt(p -> p.children().size()).sum();
    }
}
