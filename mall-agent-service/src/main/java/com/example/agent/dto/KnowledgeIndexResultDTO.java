package com.example.agent.dto;

/** 触发向量入库后的返回：文档索引状态 + 任务编号。 */
public record KnowledgeIndexResultDTO(Long documentId, String docNo, Integer version, String indexStatus,
                                      String taskNo, String taskStatus) {
}
