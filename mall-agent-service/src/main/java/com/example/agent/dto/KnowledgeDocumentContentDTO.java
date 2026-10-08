package com.example.agent.dto;

/**
 * 文档正文视图：供管理端编辑抽屉回填使用。
 * <p>列表与详情接口不返回正文，避免大字段拖慢分页查询。</p>
 */
public record KnowledgeDocumentContentDTO(Long id, String docNo, String docType, String title, Integer version,
                                          String content, Integer charCount, String indexStatus, String status,
                                          String visibility) {
}
