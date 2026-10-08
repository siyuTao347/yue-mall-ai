package com.example.agent.web;

/** RAG 领域错误码（设计文档 9.4 节），与 api.response.ErrorCodes 中的通用码配合使用。 */
public final class ErrorCodes {

    private ErrorCodes() {
    }

    public static final String RAG_DOC_NOT_FOUND = "RAG_DOC_NOT_FOUND";
    public static final String RAG_DOC_EMPTY = "RAG_DOC_EMPTY";
    public static final String RAG_DOC_DUPLICATED = "RAG_DOC_DUPLICATED";
    public static final String RAG_DOC_SENSITIVE_CONTENT = "RAG_DOC_SENSITIVE_CONTENT";
    public static final String RAG_DOC_INDEXING = "RAG_DOC_INDEXING";
    public static final String RAG_DOC_TYPE_INVALID = "RAG_DOC_TYPE_INVALID";
    public static final String RAG_SPLIT_FAILED = "RAG_SPLIT_FAILED";
    public static final String RAG_EMBEDDING_UNAVAILABLE = "RAG_EMBEDDING_UNAVAILABLE";
    public static final String RAG_VECTOR_DIM_MISMATCH = "RAG_VECTOR_DIM_MISMATCH";
    public static final String RAG_VECTOR_STORE_UNAVAILABLE = "RAG_VECTOR_STORE_UNAVAILABLE";
    public static final String RAG_TASK_CONFLICT = "RAG_TASK_CONFLICT";
    public static final String RAG_TASK_NOT_FOUND = "RAG_TASK_NOT_FOUND";
    public static final String RAG_CHUNK_NOT_FOUND = "RAG_CHUNK_NOT_FOUND";
    public static final String RAG_RETRIEVAL_MODE_UNSUPPORTED = "RAG_RETRIEVAL_MODE_UNSUPPORTED";
}
