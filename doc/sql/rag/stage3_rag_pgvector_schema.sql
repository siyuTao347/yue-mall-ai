-- =====================================================================================
-- 阶段三 RAG 知识库 —— PostgreSQL / pgvector 向量库结构与初始化
-- -------------------------------------------------------------------------------------
-- 对应设计文档：doc/agent/RAG/rag_development_design.md 第 3 章、第 7.6 节
-- 实例信息（来自部署环境）：
--   容器      : 0444598a7276
--   镜像      : registry.cn-hangzhou.aliyuncs.com/xfg-studio/pgvector:v0.5.0
--   地址端口  : 100.121.74.115:5432   (0.0.0.0:5432->5432/tcp)
--   账号 / 密码: root / root          （生产必须修改）
--   默认库    : postgres             （可选新建 db_rag 做隔离）
--   扩展      : vector 0.5.0         （支持 HNSW 索引）
-- -------------------------------------------------------------------------------------
-- 执行方式（容器内）：
--   docker exec -i 0444598a7276 psql -U root -d postgres < stage3_rag_pgvector_schema.sql
--
-- 或先进入容器：
--   docker exec -it 0444598a7276 psql -U root -d postgres
--   \i /path/to/stage3_rag_pgvector_schema.sql
--
-- 幂等性：全部使用 IF NOT EXISTS，可重复执行
-- 注意：CREATE EXTENSION 需要在「目标数据库」内执行，且需要超级用户权限（root 满足）
-- =====================================================================================

-- -------------------------------------------------------------------------------------
-- 0. 可选：独立数据库（与业务库隔离；也可直接使用 postgres 库）
--    注意：CREATE DATABASE 不能在事务块中执行
-- -------------------------------------------------------------------------------------
-- CREATE DATABASE db_rag ENCODING 'UTF8';

-- 若使用独立库，请先切换连接再执行后续语句：
-- \c db_rag

-- -------------------------------------------------------------------------------------
-- 1. 启用 pgvector 扩展
-- -------------------------------------------------------------------------------------
CREATE EXTENSION IF NOT EXISTS vector;

-- -------------------------------------------------------------------------------------
-- 2. 向量表
--    只存「最小必要字段」：向量 + 过滤列，不存正文，随时可由 MySQL 重建
--    chunk_id 对应 MySQL db_agent.t_knowledge_chunk.id（仅子片，chunk_level = 2）
-- -------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS rag_chunk_embedding (
    chunk_id             bigint PRIMARY KEY,
    document_id          bigint       NOT NULL,
    doc_no               varchar(64)  NOT NULL,
    doc_version          int          NOT NULL,
    doc_type             varchar(24)  NOT NULL,
    chunk_no             varchar(80)  NOT NULL,
    embedding            vector(1536) NOT NULL,
    embedding_model      varchar(64)  NOT NULL,
    embedding_dim        int          NOT NULL DEFAULT 1536,
    embedding_input_hash char(64)     NOT NULL,
    status               varchar(16)  NOT NULL DEFAULT 'READY',
    created_time         timestamptz  NOT NULL DEFAULT now(),
    updated_time         timestamptz  NOT NULL DEFAULT now()
);

-- -------------------------------------------------------------------------------------
-- 3. 过滤列索引
-- -------------------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_rag_emb_type_status  ON rag_chunk_embedding (doc_type, status);
CREATE INDEX IF NOT EXISTS idx_rag_emb_doc_version  ON rag_chunk_embedding (doc_no, doc_version);
CREATE INDEX IF NOT EXISTS idx_rag_emb_model        ON rag_chunk_embedding (embedding_model, embedding_dim);
CREATE INDEX IF NOT EXISTS idx_rag_emb_hash         ON rag_chunk_embedding (embedding_input_hash);

-- -------------------------------------------------------------------------------------
-- 4. 向量索引：HNSW（推荐，pgvector >= 0.5.0 支持）
--    m                : 每层邻居数，越大召回越高、索引越大（默认 16）
--    ef_construction  : 建索引候选队列，越大索引质量越好、构建越慢（默认 64）
--    查询时设置 hnsw.ef_search（设计文档建议 100，对应 TopK 20）
--    维度上限：HNSW 索引最多支持 2000 维（本表 1536 维满足）
-- -------------------------------------------------------------------------------------
-- 数据量较大时可临时提升构建资源（会话级）：
-- SET maintenance_work_mem = '512MB';
-- SET max_parallel_maintenance_workers = 4;

CREATE INDEX IF NOT EXISTS idx_rag_emb_hnsw
    ON rag_chunk_embedding
    USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);

-- -------------------------------------------------------------------------------------
-- 5. 备选：ivfflat（数据量 > 百万，或维度 > 2000 无法建 HNSW 时使用）
--    与 HNSW 二选一，不要同时建两个向量索引
-- -------------------------------------------------------------------------------------
-- CREATE INDEX IF NOT EXISTS idx_rag_emb_ivfflat
--     ON rag_chunk_embedding
--     USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);

-- -------------------------------------------------------------------------------------
-- 6. 执行后校验
-- -------------------------------------------------------------------------------------
-- 6.1 扩展版本，应为 0.5.0
-- SELECT extname, extversion FROM pg_extension WHERE extname = 'vector';

-- 6.2 PostgreSQL 版本（确认容器基础版本）
-- SELECT version();

-- 6.3 向量语法与余弦距离算子
-- SELECT '[1,2,3]'::vector <=> '[1,2,4]'::vector AS cosine_distance;   -- 约 0.00853

-- 6.4 表与索引
-- \d rag_chunk_embedding
-- SELECT indexname, indexdef FROM pg_indexes WHERE tablename = 'rag_chunk_embedding';

-- 6.5 向量条数（应与 MySQL 中 chunk_level=2 且 embedding_status='READY' 的数量一致）
-- SELECT COUNT(*) AS vector_count FROM rag_chunk_embedding;
-- SELECT doc_type, COUNT(*) FROM rag_chunk_embedding GROUP BY doc_type;

-- 6.6 检索样例（余弦距离升序 = 相似度降序）
-- BEGIN;
-- SET LOCAL hnsw.ef_search = 100;
-- SELECT chunk_id, doc_no, doc_version, doc_type,
--        1 - (embedding <=> (SELECT embedding FROM rag_chunk_embedding LIMIT 1)) AS score
--   FROM rag_chunk_embedding
--  WHERE status = 'READY'
--  ORDER BY embedding <=> (SELECT embedding FROM rag_chunk_embedding LIMIT 1)
--  LIMIT 5;
-- COMMIT;

-- -------------------------------------------------------------------------------------
-- 7. 维护说明
-- -------------------------------------------------------------------------------------
-- 7.1 写入必须显式转换类型（JDBC 传入 String 无法被推断为 vector）：
--     INSERT INTO rag_chunk_embedding (chunk_id, ..., embedding, ...)
--     VALUES (?, ?, ..., ?::vector, ?, ...)
--     ON CONFLICT (chunk_id) DO UPDATE SET embedding = EXCLUDED.embedding, ...;
--
-- 7.2 更换 Embedding 模型或维度：
--     列维度固定，无法原地变更已有数据。推荐做法：
--       a) 新建 rag_chunk_embedding_v2 (embedding vector(新维度))
--       b) 全量重建向量
--       c) 校验通过后 RENAME 切换
--     维度 > 2000 时不可使用 HNSW，需改用 ivfflat 或降维。
--
-- 7.3 清理：
--     删除文档：DELETE FROM rag_chunk_embedding WHERE doc_no = ?;
--     删除版本：DELETE FROM rag_chunk_embedding WHERE doc_no = ? AND doc_version = ?;
--     孤儿向量（MySQL 已删除的分片）由对账任务清理（设计文档 7.7 节）。
--
-- 7.4 连接配置（应用侧）：见 doc/sql/stage3_rag_database_setup.md
