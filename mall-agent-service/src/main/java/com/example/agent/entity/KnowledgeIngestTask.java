package com.example.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** RAG 向量化任务，对应 db_agent.t_knowledge_ingest_task。 */
@Data
@TableName("t_knowledge_ingest_task")
public class KnowledgeIngestTask {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String taskNo;
    private Long documentId;
    private String docNo;
    private Integer docVersion;
    private String taskType;
    private String status;
    private Integer totalChunks;
    private Integer processedChunks;
    private Integer failedChunks;
    private Integer retryCount;
    private String lastError;
    private Long operatorId;
    private LocalDateTime startedTime;
    private LocalDateTime finishedTime;
    private LocalDateTime createdTime;
    private LocalDateTime updatedTime;
}
