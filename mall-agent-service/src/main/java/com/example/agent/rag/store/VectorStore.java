package com.example.agent.rag.store;

import java.util.Collection;
import java.util.List;

/** 向量库抽象。默认实现为 pgvector；接口保持不变即可平滑替换为其他向量库。 */
public interface VectorStore {

    /** 批量 upsert，按 chunk_id 幂等覆盖。 */
    void upsertAll(List<ChunkVector> vectors);

    /** 子片级向量检索，按余弦相似度降序返回 TopK。 */
    List<VectorHit> search(float[] queryVector, VectorSearchFilter filter, int topK);

    void deleteByChunkIds(Collection<Long> chunkIds);

    void deleteByDoc(String docNo, Integer docVersion);

    /** 统计某文档版本的向量条数，用于删除后校验是否仍有残留。 */
    long countByDoc(String docNo, Integer docVersion);

    long count();

    /** 返回 pgvector 扩展版本；不可用或未安装时返回 null。 */
    String extensionVersion();

    /** 连通性与扩展可用性自检。 */
    default boolean ping() {
        return extensionVersion() != null;
    }
}
