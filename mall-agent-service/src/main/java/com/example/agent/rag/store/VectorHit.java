package com.example.agent.rag.store;

/** 向量检索命中结果（子片粒度）。 */
public record VectorHit(long chunkId, String docNo, int docVersion, String docType,
                        String chunkNo, double score) {
}
