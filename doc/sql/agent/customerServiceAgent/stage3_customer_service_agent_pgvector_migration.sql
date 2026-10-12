-- =====================================================================================
-- 阶段三智能客服助手 —— PostgreSQL / pgvector 检索安全字段迁移
-- -------------------------------------------------------------------------------------
-- 对应设计文档：doc/agent/customerServiceAgent/implementation_design.md 第 10 章
-- 目标表：rag_chunk_embedding
-- 执行库：当前 RAG 向量库
-- 执行方式：
--   docker exec -i <pgvector-container> psql -U root -d postgres \
--     < doc/sql/stage3_customer_service_agent_pgvector_migration.sql
--
-- 说明：
--   1. 用户侧 searchKnowledge 工具必须在向量查询层过滤 visibility='PUBLIC'。
--   2. 现有向量默认回填为 PUBLIC。若历史数据中已经索引进 INTERNAL 文档，
--      必须先按 doc_no 手工修正，或重新执行向量化任务。
--   3. 最新版本过滤由应用侧传入允许的 (doc_no, doc_version) 集合，本表不保存 latest 标记。
-- =====================================================================================

BEGIN;

ALTER TABLE rag_chunk_embedding
    ADD COLUMN IF NOT EXISTS visibility varchar(16) NOT NULL DEFAULT 'PUBLIC';

UPDATE rag_chunk_embedding
   SET visibility = 'PUBLIC'
 WHERE visibility IS NULL;

CREATE INDEX IF NOT EXISTS idx_rag_emb_visibility_type
    ON rag_chunk_embedding (visibility, status, doc_type);

CREATE INDEX IF NOT EXISTS idx_rag_emb_visibility_doc_version
    ON rag_chunk_embedding (visibility, doc_no, doc_version);

COMMENT ON COLUMN rag_chunk_embedding.visibility IS
    'PUBLIC=用户侧可检索, INTERNAL=仅管理或风控场景';

COMMIT;

-- -------------------------------------------------------------------------------------
-- 执行后校验
-- -------------------------------------------------------------------------------------
-- SELECT visibility, COUNT(*) FROM rag_chunk_embedding GROUP BY visibility;
-- SELECT indexname, indexdef
--   FROM pg_indexes
--  WHERE tablename = 'rag_chunk_embedding'
--    AND indexname IN ('idx_rag_emb_visibility_type', 'idx_rag_emb_visibility_doc_version');
