package com.example.agent.rag.store;

import com.example.agent.config.RagProperties;
import com.example.agent.rag.embed.VectorFormat;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * pgvector 向量库实现。
 * <p>关键点：JDBC 传入的字符串必须显式 {@code ?::vector} 转换，否则 PostgreSQL 无法推断参数类型。</p>
 */
@Slf4j
@Repository
public class PgVectorStore implements VectorStore {

    private static final int UPSERT_BATCH = 100;
    private static final int DELETE_BATCH = 500;

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final RagProperties ragProperties;

    public PgVectorStore(@Qualifier("vectorJdbcTemplate") JdbcTemplate jdbcTemplate,
                         @Qualifier("vectorTransactionTemplate") TransactionTemplate transactionTemplate,
                         RagProperties ragProperties) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.ragProperties = ragProperties;
    }

    @Override
    public void upsertAll(List<ChunkVector> vectors) {
        if (vectors == null || vectors.isEmpty()) {
            return;
        }
        for (int i = 0; i < vectors.size(); i += UPSERT_BATCH) {
            List<ChunkVector> batch = vectors.subList(i, Math.min(i + UPSERT_BATCH, vectors.size()));
            upsertBatch(batch);
        }
    }

    private void upsertBatch(List<ChunkVector> batch) {
        StringBuilder sql = new StringBuilder(
                "INSERT INTO rag_chunk_embedding (chunk_id, document_id, doc_no, doc_version, doc_type, "
                        + "chunk_no, embedding, embedding_model, embedding_dim, embedding_input_hash, status, "
                        + "created_time, updated_time) VALUES ");
        List<Object> params = new ArrayList<>(batch.size() * 11);
        for (int i = 0; i < batch.size(); i++) {
            if (i > 0) {
                sql.append(',');
            }
            sql.append("(?,?,?,?,?,?,?::vector,?,?,?,?,now(),now())");
            ChunkVector vector = batch.get(i);
            params.add(vector.chunkId());
            params.add(vector.documentId());
            params.add(vector.docNo());
            params.add(vector.docVersion());
            params.add(vector.docType());
            params.add(vector.chunkNo());
            params.add(VectorFormat.toLiteral(vector.embedding()));
            params.add(vector.model());
            params.add(vector.dim());
            params.add(vector.inputHash());
            params.add(vector.status());
        }
        sql.append(" ON CONFLICT (chunk_id) DO UPDATE SET embedding = EXCLUDED.embedding, "
                + "embedding_model = EXCLUDED.embedding_model, embedding_dim = EXCLUDED.embedding_dim, "
                + "embedding_input_hash = EXCLUDED.embedding_input_hash, doc_no = EXCLUDED.doc_no, "
                + "doc_version = EXCLUDED.doc_version, doc_type = EXCLUDED.doc_type, "
                + "chunk_no = EXCLUDED.chunk_no, status = EXCLUDED.status, updated_time = now()");
        jdbcTemplate.update(sql.toString(), params.toArray());
    }

    @Override
    public List<VectorHit> search(float[] queryVector, VectorSearchFilter filter, int topK) {
        if (queryVector == null || queryVector.length == 0 || topK <= 0) {
            return List.of();
        }
        String literal = VectorFormat.toLiteral(queryVector);
        String status = filter.status() == null ? "READY" : filter.status();
        StringBuilder sql = new StringBuilder("SELECT chunk_id, doc_no, doc_version, doc_type, chunk_no, "
                + "1 - (embedding <=> ?::vector) AS score FROM rag_chunk_embedding "
                + "WHERE status = ? AND embedding_model = ? AND embedding_dim = ?");
        List<Object> params = new ArrayList<>();
        params.add(literal);
        params.add(status);
        params.add(filter.model());
        params.add(filter.dim());
        if (filter.hasDocTypes()) {
            sql.append(" AND doc_type IN (")
                    .append(filter.docTypes().stream().map(t -> "?").collect(Collectors.joining(",")))
                    .append(')');
            params.addAll(filter.docTypes());
        }
        sql.append(" ORDER BY embedding <=> ?::vector LIMIT ?");
        params.add(literal);
        params.add(topK);

        List<VectorHit> hits = transactionTemplate.execute(status0 -> {
            applySearchEf();
            return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> new VectorHit(
                            rs.getLong("chunk_id"), rs.getString("doc_no"), rs.getInt("doc_version"),
                            rs.getString("doc_type"), rs.getString("chunk_no"), rs.getDouble("score")),
                    params.toArray());
        });
        return hits == null ? List.of() : hits;
    }

    /** hnsw.ef_search 是查询期参数，必须在事务内用 SET LOCAL，避免污染连接池中的其他连接。 */
    private void applySearchEf() {
        int efSearch = ragProperties.getPgvector().getIndex().getSearchEf();
        if (efSearch <= 0) {
            return;
        }
        try {
            jdbcTemplate.execute("SET LOCAL hnsw.ef_search = " + efSearch);
        } catch (Exception e) {
            log.warn("[RAG] 设置 hnsw.ef_search 失败（pgvector 版本可能低于 0.5.0），使用默认值: {}", e.getMessage());
        }
    }

    @Override
    public void deleteByChunkIds(Collection<Long> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return;
        }
        List<Long> ids = new ArrayList<>(chunkIds);
        for (int i = 0; i < ids.size(); i += DELETE_BATCH) {
            List<Long> batch = ids.subList(i, Math.min(i + DELETE_BATCH, ids.size()));
            String placeholders = batch.stream().map(id -> "?").collect(Collectors.joining(","));
            jdbcTemplate.update("DELETE FROM rag_chunk_embedding WHERE chunk_id IN (" + placeholders + ")",
                    batch.toArray());
        }
    }

    @Override
    public void deleteByDoc(String docNo, Integer docVersion) {
        if (docVersion == null) {
            jdbcTemplate.update("DELETE FROM rag_chunk_embedding WHERE doc_no = ?", docNo);
        } else {
            jdbcTemplate.update("DELETE FROM rag_chunk_embedding WHERE doc_no = ? AND doc_version = ?",
                    docNo, docVersion);
        }
    }

    @Override
    public long count() {
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM rag_chunk_embedding", Long.class);
        return total == null ? 0L : total;
    }

    @Override
    public long countByDoc(String docNo, Integer docVersion) {
        Long total = docVersion == null
                ? jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM rag_chunk_embedding WHERE doc_no = ?", Long.class, docNo)
                : jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM rag_chunk_embedding WHERE doc_no = ? AND doc_version = ?",
                        Long.class, docNo, docVersion);
        return total == null ? 0L : total;
    }

    @Override
    public String extensionVersion() {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT extversion FROM pg_extension WHERE extname = 'vector'", String.class);
        } catch (Exception e) {
            log.warn("[RAG] pgvector 自检失败: {}", e.getMessage());
            return null;
        }
    }
}
