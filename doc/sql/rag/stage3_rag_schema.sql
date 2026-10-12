-- =====================================================================================
-- 阶段三 RAG 知识库 —— MySQL 元数据库结构与初始化
-- -------------------------------------------------------------------------------------
-- 对应设计文档：doc/agent/RAG/rag_development_design.md 第 7.2 ~ 7.5 节
-- 目标库：db_agent（阶段三 Agent 服务独立库）
-- 数据库版本：MySQL 8.0（ngram 全文索引需 8.0，可选）
-- 执行方式：项目未接入 Flyway/Liquibase，请在发布前按环境手工执行一次
-- 幂等性：全部使用 CREATE TABLE IF NOT EXISTS，可重复执行
-- -------------------------------------------------------------------------------------
-- 说明：
--   1. 本脚本只负责 RAG 相关知识库表；阶段三 Agent 会话/运行/工具调用等基础表
--      （t_agent_session / t_agent_message / t_agent_run / t_agent_tool_call /
--       t_agent_draft / t_agent_handoff / t_agent_eval_case / t_agent_eval_result /
--       t_agent_feedback）见 doc/agent/stage3_agent_development_design.md 第 8.2 节，
--      它们与 RAG 表同库（db_agent），需一并创建。
--   2. 向量数据不落 MySQL，存于 PostgreSQL + pgvector，见
--      doc/sql/stage3_rag_pgvector_schema.sql。
-- =====================================================================================

CREATE DATABASE IF NOT EXISTS `db_agent` DEFAULT CHARACTER SET utf8mb4;

USE `db_agent`;

-- -------------------------------------------------------------------------------------
-- 1. 知识文档表
--    权威源：文档正文、版本、可见性、切片与索引状态
-- -------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_knowledge_document` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `doc_no` varchar(64) NOT NULL COMMENT '文档编号，如 PROD-001 / RULE-001',
    `doc_type` varchar(24) NOT NULL COMMENT 'PRODUCT, RULE, POLICY, AFTER_SALE, RISK, FAQ',
    `title` varchar(128) NOT NULL,
    `content` mediumtext NOT NULL COMMENT 'Markdown 原文（不含 front-matter）',
    `content_hash` char(64) NOT NULL COMMENT '归一化正文 sha256，用于判断是否需要重新切片',
    `version` int NOT NULL DEFAULT 1,
    `splitter_version` varchar(16) NOT NULL DEFAULT 'v1' COMMENT '切片算法版本，变更后必须全量重建',
    `source_type` varchar(16) NOT NULL DEFAULT 'MANUAL' COMMENT 'MANUAL, UPLOAD, BUILT_IN',
    `source_path` varchar(255) DEFAULT NULL COMMENT '导入来源路径，如 doc/data/RAG/PROD-001_AK-47-红线.md',
    `char_count` int NOT NULL DEFAULT 0,
    `parent_chunk_count` int NOT NULL DEFAULT 0,
    `child_chunk_count` int NOT NULL DEFAULT 0,
    `index_status` varchar(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING, SPLIT, INDEXING, READY, PARTIAL, FAILED',
    `visibility` varchar(16) NOT NULL DEFAULT 'PUBLIC' COMMENT 'PUBLIC=用户侧可检索, INTERNAL=仅管理/风控场景',
    `status` varchar(16) NOT NULL DEFAULT 'DISABLED' COMMENT 'ENABLED, DISABLED',
    `created_by` bigint unsigned NOT NULL,
    `updated_by` bigint unsigned NOT NULL,
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_doc_no_version` (`doc_no`, `version`),
    KEY `idx_type_status` (`doc_type`, `status`),
    KEY `idx_index_status` (`index_status`),
    KEY `idx_visibility_type` (`visibility`, `doc_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent 知识文档表';

-- -------------------------------------------------------------------------------------
-- 2. 知识分片表（父子两层）
--    chunk_level=1 父片（按小节，不向量化，检索后作为上下文返回）
--    chunk_level=2 子片（按段落，向量化，仅用于召回）
-- -------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_knowledge_chunk` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `document_id` bigint unsigned NOT NULL,
    `doc_no` varchar(64) NOT NULL,
    `doc_version` int NOT NULL,
    `chunk_level` tinyint NOT NULL COMMENT '1=父片(L1), 2=子片(L2)',
    `parent_chunk_id` bigint unsigned DEFAULT NULL COMMENT '子片所属父片 id；父片为 NULL',
    `chunk_no` varchar(80) NOT NULL COMMENT '如 PROD-001-v1-P003-C02',
    `chunk_index` int NOT NULL COMMENT '同层级内序号，从 1 开始',
    `chunk_hash` char(64) NOT NULL,
    `title_path` varchar(255) NOT NULL COMMENT '标题路径，如 AK-47 | 红线/7. 价格与费用',
    `chunk_content` text NOT NULL,
    `char_count` int NOT NULL DEFAULT 0,
    `char_start` int NOT NULL DEFAULT 0 COMMENT '在文档归一化正文中的起始偏移',
    `char_end` int NOT NULL DEFAULT 0,
    `keywords_json` json DEFAULT NULL COMMENT '关键词与权重，供降级检索使用',
    `embedding_status` varchar(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING, READY, FAILED, SKIPPED',
    `embedding_model` varchar(64) DEFAULT NULL,
    `embedding_dim` int DEFAULT NULL,
    `embedding_input_hash` char(64) DEFAULT NULL COMMENT '用于判断向量是否可复用',
    `split_forced` tinyint NOT NULL DEFAULT 0 COMMENT '是否触发了硬切分',
    `token_count` int NOT NULL DEFAULT 0,
    `status` varchar(16) NOT NULL DEFAULT 'ENABLED' COMMENT 'ENABLED, DISABLED',
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_chunk_hash` (`chunk_hash`),
    UNIQUE KEY `uk_chunk_no` (`chunk_no`),
    KEY `idx_document_level` (`document_id`, `chunk_level`, `status`),
    KEY `idx_parent` (`parent_chunk_id`),
    KEY `idx_doc_version` (`doc_no`, `doc_version`, `status`),
    KEY `idx_embedding_status` (`embedding_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent 知识分片表（父子两层）';

-- -------------------------------------------------------------------------------------
-- 3. 向量化任务表
-- -------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_knowledge_ingest_task` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `task_no` varchar(64) NOT NULL,
    `document_id` bigint unsigned NOT NULL,
    `doc_no` varchar(64) NOT NULL,
    `doc_version` int NOT NULL,
    `task_type` varchar(16) NOT NULL COMMENT 'SPLIT, EMBED, REBUILD, DELETE, REINDEX',
    `status` varchar(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING, RUNNING, SUCCESS, PARTIAL, FAILED, CANCELED',
    `total_chunks` int NOT NULL DEFAULT 0,
    `processed_chunks` int NOT NULL DEFAULT 0,
    `failed_chunks` int NOT NULL DEFAULT 0,
    `retry_count` int NOT NULL DEFAULT 0,
    `last_error` varchar(512) DEFAULT NULL,
    `operator_id` bigint unsigned NOT NULL,
    `started_time` datetime(3) DEFAULT NULL,
    `finished_time` datetime(3) DEFAULT NULL,
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_task_no` (`task_no`),
    KEY `idx_doc_time` (`doc_no`, `doc_version`, `created_time`),
    KEY `idx_status_time` (`status`, `created_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='RAG 向量化任务表';

-- -------------------------------------------------------------------------------------
-- 4. 检索日志表（检索测试与召回效果分析）
-- -------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_knowledge_retrieval_log` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `log_no` varchar(64) NOT NULL,
    `scene` varchar(24) NOT NULL COMMENT 'TEST, CUSTOMER_SERVICE, ARBITRATION, RISK, LISTING',
    `query_text` varchar(512) NOT NULL,
    `retrieval_mode` varchar(16) NOT NULL COMMENT 'KEYWORD, VECTOR, HYBRID, DEGRADED',
    `requested_mode` varchar(16) NOT NULL,
    `top_k` int NOT NULL,
    `child_hit_count` int NOT NULL DEFAULT 0,
    `parent_hit_count` int NOT NULL DEFAULT 0,
    `latency_ms` int NOT NULL DEFAULT 0,
    `result_json` json DEFAULT NULL,
    `operator_id` bigint unsigned DEFAULT NULL,
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_log_no` (`log_no`),
    KEY `idx_scene_time` (`scene`, `created_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='RAG 检索日志表';

-- =====================================================================================
-- 5. 可选：中文全文索引（关键词检索增强）
-- -------------------------------------------------------------------------------------
-- 关键词检索是混合检索的第二路召回，也是 pgvector 不可用时的降级路径。
-- MySQL 8.0 自带 ngram 分词插件，可对中文做全文检索。
--
-- 前置检查：
--   SHOW VARIABLES LIKE 'ngram_token_size';          -- 默认 2，中文建议 2
--   SELECT * FROM information_schema.plugins WHERE plugin_name = 'ngram';
--
-- 若插件可用且 ngram_token_size = 2，执行以下语句建立全文索引：
--
-- ALTER TABLE `t_knowledge_chunk`
--     ADD FULLTEXT KEY `ft_chunk` (`chunk_content`) WITH PARSER ngram;
--
-- ALTER TABLE `t_knowledge_document`
--     ADD FULLTEXT KEY `ft_content` (`content`) WITH PARSER ngram;
--
-- 若插件不可用（或 ngram_token_size 非 2），跳过本节：
--   检索降级为 `keywords_json + LIKE` 加权打分（标题命中 ×5 / 标签 ×3 / 正文 ×1），
--   功能可用但精度下降，不需要修改任何表结构。
-- =====================================================================================

-- =====================================================================================
-- 6. 执行后校验
-- =====================================================================================
-- SHOW TABLES LIKE 't_knowledge%';
-- SELECT COUNT(*) AS doc_count FROM t_knowledge_document;
-- SELECT doc_no, doc_type, version, parent_chunk_count, child_chunk_count, index_status, visibility
--   FROM t_knowledge_document ORDER BY id DESC LIMIT 20;
-- SELECT chunk_level, COUNT(*) FROM t_knowledge_chunk GROUP BY chunk_level;
-- -- 子片必须挂在父片上，结果应为 0
-- SELECT COUNT(*) AS orphan_child FROM t_knowledge_chunk
--  WHERE chunk_level = 2 AND parent_chunk_id IS NULL;
-- -- 父子必须同文档同版本，结果应为 0
-- SELECT COUNT(*) AS cross_version FROM t_knowledge_chunk c
--   JOIN t_knowledge_chunk p ON p.id = c.parent_chunk_id
--  WHERE c.document_id <> p.document_id OR c.doc_version <> p.doc_version;
