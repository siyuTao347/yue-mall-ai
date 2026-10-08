package com.example.agent.dto;

import java.time.LocalDateTime;

/** 知识文档视图。 */
public record KnowledgeDocumentDTO(Long id, String docNo, String docType, String title, Integer version,
                                   String splitterVersion, String sourceType, String sourcePath,
                                   Integer charCount, Integer parentChunkCount, Integer childChunkCount,
                                   String indexStatus, String visibility, String status,
                                   LocalDateTime createdTime, LocalDateTime updatedTime) {
}
