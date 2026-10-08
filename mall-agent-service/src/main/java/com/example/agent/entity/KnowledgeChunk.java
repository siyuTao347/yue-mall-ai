package com.example.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * RAG 知识分片，对应 db_agent.t_knowledge_chunk。
 * <p>chunkLevel=1 父片（按小节，作为上下文返回），chunkLevel=2 子片（按段落，仅用于向量召回）。</p>
 */
@Data
@TableName("t_knowledge_chunk")
public class KnowledgeChunk {
    public static final int LEVEL_PARENT = 1;
    public static final int LEVEL_CHILD = 2;

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long documentId;
    private String docNo;
    private Integer docVersion;
    private Integer chunkLevel;
    private Long parentChunkId;
    private String chunkNo;
    private Integer chunkIndex;
    private String chunkHash;
    private String titlePath;
    private String chunkContent;
    private Integer charCount;
    private Integer charStart;
    private Integer charEnd;
    private String keywordsJson;
    private String embeddingStatus;
    private String embeddingModel;
    private Integer embeddingDim;
    private String embeddingInputHash;
    private Integer splitForced;
    private Integer tokenCount;
    private String status;
    private LocalDateTime createdTime;
    private LocalDateTime updatedTime;
}
