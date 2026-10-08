package com.example.agent.rag.embed;

/** Embedding 调用失败，触发上层降级为关键词检索。 */
public class EmbeddingException extends RuntimeException {

    public EmbeddingException(String message, Throwable cause) {
        super(message, cause);
    }

    public EmbeddingException(String message) {
        super(message);
    }
}
