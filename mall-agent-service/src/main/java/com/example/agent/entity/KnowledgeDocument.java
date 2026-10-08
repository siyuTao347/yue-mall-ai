package com.example.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** RAG 知识文档，对应 db_agent.t_knowledge_document。 */
@Data
@TableName("t_knowledge_document")
public class KnowledgeDocument {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String docNo;
    private String docType;
    private String title;
    private String content;
    private String contentHash;
    private Integer version;
    private String splitterVersion;
    private String sourceType;
    private String sourcePath;
    private Integer charCount;
    private Integer parentChunkCount;
    private Integer childChunkCount;
    private String indexStatus;
    private String visibility;
    private String status;
    private Long createdBy;
    private Long updatedBy;
    private LocalDateTime createdTime;
    private LocalDateTime updatedTime;
}
