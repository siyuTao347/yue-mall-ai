package com.example.agent.dto;

import java.util.List;

/** 分片树节点：父片携带子片列表。 */
public record KnowledgeChunkNodeDTO(Long chunkId, String chunkNo, Integer chunkLevel, String titlePath,
                                    Integer charCount, Integer charStart, Integer charEnd,
                                    String embeddingStatus, String embeddingModel, Integer embeddingDim,
                                    Boolean splitForced, String content,
                                    List<KnowledgeChunkNodeDTO> children) {
}
