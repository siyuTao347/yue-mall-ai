-- 智能客服 L2 意图 RAG —— PostgreSQL / pgvector 增量迁移
-- 设计：doc/agent/customerServiceAgent/implementation_design.md 第 7.1、10.8、12 节
-- 执行库：当前 RAG 向量库，与 rag_chunk_embedding 同库、独立表。
-- 前置：同目录 MySQL 意图库脚本；pgvector 已安装，沿用当前 1536 维配置。
-- 执行：psql -v ON_ERROR_STOP=1 -d <rag_database> -f <本脚本路径>
-- 可重复执行，不修改已有知识向量，不伪造种子 Embedding；表为空时 L2 转 L3。
-- 初期使用精确余弦检索，不创建 ANN 索引，避免样本 TopK 漏掉竞争意图。

BEGIN;
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS rag_customer_service_intent_embedding (
    example_no           varchar(80) NOT NULL,
    example_version      int NOT NULL CHECK (example_version > 0),
    intent_code          varchar(48) NOT NULL,
    intent_version       int NOT NULL CHECK (intent_version > 0),
    embedding            vector(1536) NOT NULL,
    embedding_model      varchar(64) NOT NULL,
    embedding_dim        int NOT NULL DEFAULT 1536 CHECK (embedding_dim = 1536),
    embedding_input_hash char(64) NOT NULL CHECK (embedding_input_hash ~ '^[0-9a-f]{64}$'),
    status               varchar(16) NOT NULL DEFAULT 'READY' CHECK (status IN ('READY', 'DISABLED')),
    created_time         timestamptz NOT NULL DEFAULT now(),
    updated_time         timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (example_no, example_version, embedding_model),
    CONSTRAINT ck_cs_intent_nonzero_vector CHECK ((embedding <#> embedding) < 0)
);

CREATE INDEX IF NOT EXISTS idx_cs_intent_emb_model_status
    ON rag_customer_service_intent_embedding (embedding_model, embedding_dim, status);
CREATE INDEX IF NOT EXISTS idx_cs_intent_emb_code_version
    ON rag_customer_service_intent_embedding (intent_code, intent_version);

COMMENT ON TABLE rag_customer_service_intent_embedding IS
    'L2 意图示例派生向量；以 MySQL 有效示例集合校验后方可参与路由，不作为知识引用';
COMMENT ON COLUMN rag_customer_service_intent_embedding.embedding_input_hash IS
    'UTF-8 SHA256(embedding_model + | + normalized_text)，与 MySQL READY 记录逐项匹配';

COMMIT;

-- 写入约定：ON CONFLICT (example_no, example_version, embedding_model) DO UPDATE
-- 更新 intent_code / intent_version / embedding / embedding_dim / embedding_input_hash /
-- status / updated_time；不得只更新向量而遗漏校验字段。
-- 拒绝零向量；写入前校验维度、模型和全部数值有限，查询向量也必须验证非零。
-- PG 成功写入后，以 example_no + example_version + intent_version + example_hash
-- 条件更新 MySQL 的 embedding_model / embedding_dim / embedding_input_hash / READY。
-- PG 写入成功但 MySQL 未成功：下次重试幂等覆盖，对账修复；未 READY 记录不可检索。

-- 参数化精确查询示意（注释内 :name 由应用绑定，不是可直接执行的 SQL）：
-- :valid_examples_json 由服务端读取 MySQL 当前有效集合生成，不能由用户/模型提供。
-- 每个对象含 example_no、example_version、intent_code、intent_version、embedding_input_hash。
-- MySQL 先过滤 ENABLED、READY、版本及当前模型/维度，禁止先取样本 TopK 再校验。
-- WITH valid_examples AS (
--     SELECT * FROM jsonb_to_recordset(CAST(:valid_examples_json AS jsonb)) AS x(
--         example_no text, example_version int, intent_code text,
--         intent_version int, embedding_input_hash text
--     )
-- ), scored AS (
--     SELECT e.example_no, e.example_version, e.intent_code, e.intent_version,
--            1 - (e.embedding <=> CAST(:query_vector AS vector(1536))) AS score
--       FROM rag_customer_service_intent_embedding e
--       JOIN valid_examples v
--         ON e.example_no = v.example_no AND e.example_version = v.example_version
--        AND e.intent_code = v.intent_code AND e.intent_version = v.intent_version
--        AND e.embedding_input_hash = v.embedding_input_hash
--      WHERE e.status = 'READY' AND e.embedding_model = :embedding_model AND e.embedding_dim = 1536
-- ), best_per_intent AS (
--     SELECT DISTINCT ON (intent_code)
--            intent_code, intent_version, example_no, example_version, score
--       FROM scored
--      ORDER BY intent_code, score DESC, example_no, example_version DESC
-- )
-- SELECT * FROM best_per_intent ORDER BY score DESC, intent_code LIMIT :intent_top_k;
-- top_k >= 2；应用在查询后按 Top1 专属/全局阈值和不同意图分差作判断。
-- L2 总超时覆盖 MySQL/Embedding/PG，PG statement_timeout 使用剩余预算。

-- 执行后校验（首次执行预期 0 行向量，完成索引后按有效模型/版本与 MySQL 对账）：
-- SELECT count(*) FROM rag_customer_service_intent_embedding;
-- SELECT indexname, indexdef FROM pg_indexes
--  WHERE tablename = 'rag_customer_service_intent_embedding';
-- SELECT intent_code, embedding_model, status, count(*)
--   FROM rag_customer_service_intent_embedding GROUP BY intent_code, embedding_model, status;
-- 维度变化时另建对应维度的表、重建并校准后切换，不能仅修改 embedding_dim 字段。
