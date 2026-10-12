-- 智能客服意图 RAG —— 完整版本发布增量迁移（PostgreSQL / pgvector）
-- 对应 implementation_design.md 第 10.8、12 节。
-- 前置：已执行旧意图 PG 脚本及同目录 release_mysql_migration.sql。
-- 目标：原 RAG PostgreSQL 数据库，vector 扩展和 1536 维基线保持不变。
-- 执行：psql -v ON_ERROR_STOP=1 -d <rag_database> -f <本脚本路径>
-- 只新建发布向量表，不 ALTER、清空或覆盖已存在的 rag_customer_service_intent_embedding。
-- 不自动复制旧向量/发布初始数据；新表初始为空，完整发布前 L2 转 L3。

BEGIN;

CREATE TABLE IF NOT EXISTS rag_customer_service_intent_release_embedding (
    release_no           varchar(64) NOT NULL,
    example_no           varchar(80) NOT NULL,
    example_version      int NOT NULL CHECK (example_version > 0),
    intent_code          varchar(48) NOT NULL,
    intent_version       int NOT NULL CHECK (intent_version > 0),
    embedding            vector(1536) NOT NULL,
    embedding_model      varchar(64) NOT NULL,
    embedding_dim        int NOT NULL DEFAULT 1536 CHECK (embedding_dim = 1536),
    embedding_input_hash char(64) NOT NULL CHECK (embedding_input_hash ~ '^[0-9a-f]{64}$'),
    created_time         timestamptz NOT NULL DEFAULT now(),
    updated_time         timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (release_no, example_no),
    CONSTRAINT ck_cs_release_vector_nonzero CHECK ((embedding <#> embedding) < 0)
);

CREATE INDEX IF NOT EXISTS idx_cs_release_vector_intent
    ON rag_customer_service_intent_release_embedding (release_no, intent_code);

COMMENT ON TABLE rag_customer_service_intent_release_embedding IS
    '完整发布版本专属向量；同版示例仅一行，按完整清单校验后排名，不允许部分可用';
COMMENT ON COLUMN rag_customer_service_intent_release_embedding.release_no IS
    '关联 MySQL 发布头；仅 BUILDING 可写，VALIDATING/READY 后停止写入，线上只读锁定版本';

COMMIT;

-- 写入约定：ON CONFLICT (release_no, example_no) DO UPDATE 全部向量及元数据。
-- 仅允许在固定 release_no 仍 BUILDING 时幂等写入，模型/文本/hash 必须匹配冻结清单。
-- 构建任务和发布流程使用同一发布级任务锁；VALIDATING 前等待在途任务写入结束。
-- 不能只在开始任务时检查状态，结束时无条件写入；READY 后不得覆盖或补写，失败重建新版本。
-- 已发布向量表不设逐行 ENABLED 标志，禁止禁用一条后把缩小的集合用于在线识别。

-- 下面为在线查询模板，:name 均由服务端绑定，不是本迁移自动执行的 SQL。
-- :expected_examples_json 来自锁定 release_no 的完整 MySQL 快照，非用户或模型输入；
-- 服务端须先核对 MySQL 清单 hash、总数、每意图数量以及模型/路由/阈值。
-- 查询向量也必须验证模型匹配、维度正确、非零且全部数值有限。
-- 所有 CTE 在同一条语句的数据库快照内读取，计数与候选不能拆成两个查询。
-- WITH expected AS (
--     SELECT * FROM jsonb_to_recordset(CAST(:expected_examples_json AS jsonb)) AS x(
--         example_no text, example_version int, intent_code text,
--         intent_version int, embedding_input_hash text
--     )
-- ), scored AS (
--     SELECT v.example_no, v.example_version, v.intent_code, v.intent_version,
--            1 - (v.embedding <=> CAST(:query_vector AS vector(1536))) AS score
--       FROM rag_customer_service_intent_release_embedding v
--       JOIN expected e ON v.example_no=e.example_no AND v.example_version=e.example_version
--        AND v.intent_code=e.intent_code AND v.intent_version=e.intent_version
--        AND v.embedding_input_hash=e.embedding_input_hash
--      WHERE v.release_no=:release_no AND v.embedding_model=:embedding_model AND v.embedding_dim=1536
-- ), integrity AS (
--     SELECT
--       (SELECT COUNT(*) FROM expected) AS manifest_count,
--       (SELECT COUNT(DISTINCT example_no) FROM expected) AS unique_example_count,
--       (SELECT COUNT(*) FROM rag_customer_service_intent_release_embedding
--         WHERE release_no=:release_no) AS actual_count,
--       COUNT(*) AS matched_count,
--       COUNT(DISTINCT intent_code) AS matched_intent_count
--     FROM scored
-- ), best_per_intent AS (
--     SELECT DISTINCT ON (intent_code) intent_code, intent_version, example_no, example_version, score
--       FROM scored ORDER BY intent_code, score DESC, example_no
-- ), candidates AS (
--     SELECT b.* FROM best_per_intent b CROSS JOIN integrity i
--      WHERE i.manifest_count=:expected_example_count
--        AND i.unique_example_count=:expected_example_count
--        AND i.actual_count=:expected_example_count
--        AND i.matched_count=:expected_example_count
--        AND i.matched_intent_count=:expected_intent_count
--        AND i.matched_intent_count >= 2
--      ORDER BY b.score DESC, b.intent_code LIMIT :intent_top_k
-- )
-- SELECT i.*, COALESCE(
--     (SELECT jsonb_agg(to_jsonb(c) ORDER BY c.score DESC, c.intent_code) FROM candidates c),
--     '[]'::jsonb) AS candidates
--   FROM integrity i;
-- 返回恰好一行计数，即使缺失全部向量也返回；完整性不符时 candidates=[]，整次进入 L3。
-- 完整性通过后仍须至少两个候选、score1 > 版本阈值且 score1-score2 >= 版本 min_margin。
-- intent_top_k 至少 2。查询总预算含获取连接最多 1s、SQL 使用剩余 PG 预算，总共最多 2s；
-- 还须受 L2 12s 与 run 90s 剩余时间约束，超时转 L3，不等待底层默认 10s。

-- 执行后检查（首次执行预期 0，重复执行保持已有发布向量）：
SELECT COUNT(*) AS release_vector_count FROM rag_customer_service_intent_release_embedding;
