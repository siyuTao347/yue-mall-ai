-- CustomerAgent v2: keyword index and response timing/cache metrics.
-- Run against db_agent after confirming the MySQL version supports the ngram parser.
USE `db_agent`;

SET @has_fulltext = (
    SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 't_knowledge_chunk'
       AND index_name = 'ft_chunk_content_title'
);
SET @ddl = IF(@has_fulltext = 0,
    'ALTER TABLE `t_knowledge_chunk` ADD FULLTEXT KEY `ft_chunk_content_title` (`title_path`, `chunk_content`) WITH PARSER ngram',
    'SELECT 1');
PREPARE customer_agent_stmt FROM @ddl;
EXECUTE customer_agent_stmt;
DEALLOCATE PREPARE customer_agent_stmt;

SET @has_embedding_cache_hit = (
    SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 't_agent_run'
       AND column_name = 'embedding_cache_hit'
);
SET @ddl = IF(@has_embedding_cache_hit = 0,
    'ALTER TABLE `t_agent_run` ADD COLUMN `embedding_cache_hit` tinyint NOT NULL DEFAULT 0 COMMENT ''Embedding 是否命中缓存'' AFTER `retrieval_mode`',
    'SELECT 1');
PREPARE customer_agent_stmt FROM @ddl;
EXECUTE customer_agent_stmt;
DEALLOCATE PREPARE customer_agent_stmt;

SET @has_ttft = (
    SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 't_agent_run'
       AND column_name = 'ttft_ms'
);
SET @ddl = IF(@has_ttft = 0,
    'ALTER TABLE `t_agent_run` ADD COLUMN `ttft_ms` int DEFAULT NULL COMMENT ''首字响应耗时(ms)'' AFTER `latency_ms`',
    'SELECT 1');
PREPARE customer_agent_stmt FROM @ddl;
EXECUTE customer_agent_stmt;
DEALLOCATE PREPARE customer_agent_stmt;
