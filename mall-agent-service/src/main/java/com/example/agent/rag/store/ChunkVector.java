package com.example.agent.rag.store;

/** 待写入向量库的一条记录（对应一个子片）。 */
public record ChunkVector(long chunkId, long documentId, String docNo, int docVersion, String docType,
                          String chunkNo, float[] embedding, String model, int dim, String inputHash,
                          String status) {
}
