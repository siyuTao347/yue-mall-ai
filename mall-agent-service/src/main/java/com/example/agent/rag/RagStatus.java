package com.example.agent.rag;

/** RAG 状态常量，与 doc/sql/stage3_rag_schema.sql 中的注释保持一致。 */
public final class RagStatus {

    private RagStatus() {
    }

    // 文档 status
    public static final String DOC_ENABLED = "ENABLED";
    public static final String DOC_DISABLED = "DISABLED";

    // 文档 index_status
    public static final String INDEX_PENDING = "PENDING";
    public static final String INDEX_SPLIT = "SPLIT";
    public static final String INDEX_INDEXING = "INDEXING";
    public static final String INDEX_READY = "READY";
    public static final String INDEX_PARTIAL = "PARTIAL";
    public static final String INDEX_FAILED = "FAILED";

    // 分片 embedding_status
    public static final String EMB_PENDING = "PENDING";
    public static final String EMB_READY = "READY";
    public static final String EMB_FAILED = "FAILED";
    public static final String EMB_SKIPPED = "SKIPPED";

    // 分片 status
    public static final String CHUNK_ENABLED = "ENABLED";
    public static final String CHUNK_DISABLED = "DISABLED";

    // 任务 status / task_type
    public static final String TASK_PENDING = "PENDING";
    public static final String TASK_RUNNING = "RUNNING";
    public static final String TASK_SUCCESS = "SUCCESS";
    public static final String TASK_PARTIAL = "PARTIAL";
    public static final String TASK_FAILED = "FAILED";
    /** 文档删除等场景下主动取消，工作线程按批检测后停止写入 */
    public static final String TASK_CANCELED = "CANCELED";
    public static final String TASK_TYPE_EMBED = "EMBED";
    public static final String TASK_TYPE_REBUILD = "REBUILD";

    // 可见性
    public static final String VISIBILITY_PUBLIC = "PUBLIC";
    public static final String VISIBILITY_INTERNAL = "INTERNAL";

    public static boolean isIndexed(String indexStatus) {
        return INDEX_READY.equals(indexStatus) || INDEX_PARTIAL.equals(indexStatus);
    }
}
