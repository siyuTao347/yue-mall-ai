package com.example.agent.dto;

import java.time.LocalDateTime;

/** 向量化任务视图。 */
public record KnowledgeIngestTaskDTO(Long id, String taskNo, Long documentId, String docNo, Integer docVersion,
                                     String taskType, String status, Integer totalChunks,
                                     Integer processedChunks, Integer failedChunks, Integer retryCount,
                                     String lastError, LocalDateTime startedTime, LocalDateTime finishedTime,
                                     LocalDateTime createdTime) {

    public Integer progressPercent() {
        if (totalChunks == null || totalChunks == 0) {
            return 0;
        }
        int processed = processedChunks == null ? 0 : processedChunks;
        return Math.min(100, processed * 100 / totalChunks);
    }
}
