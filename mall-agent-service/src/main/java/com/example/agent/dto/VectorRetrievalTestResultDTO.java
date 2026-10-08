package com.example.agent.dto;

import java.util.List;

/** 检索测试结果：命中子片 + 回溯父片 + 耗时。 */
public record VectorRetrievalTestResultDTO(String requestedMode, String actualMode, long latencyMs,
                                           int childTopK, List<ChildHit> childHits,
                                           List<KnowledgeChunkNodeDTO> contexts) {

    public record ChildHit(String chunkNo, long chunkId, String docNo, Integer docVersion, String docType,
                           String titlePath, double score, String content) {
    }
}
