package com.example.agent.dto;

import java.util.List;
/** 切片预览结果（不落库）。 */
public record KnowledgeSplitPreviewDTO(String docNo, Integer version, String title, int charCount,
                                       int parentCount, int childCount,
                                       List<KnowledgeChunkNodeDTO> parents) {
}
