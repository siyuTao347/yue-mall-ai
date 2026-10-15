-- =====================================================================================
-- 阶段三智能客服助手 —— MySQL Agent 数据库结构
-- -------------------------------------------------------------------------------------
-- 对应设计文档：doc/agent/customerServiceAgent/implementation_design.md
-- 目标库：db_agent
-- 数据库版本：MySQL 8.0
-- 执行顺序：先执行 doc/sql/stage3_rag_schema.sql，再执行本脚本
-- 项目说明：未接入 Flyway / Liquibase，发布前按环境手工执行并留存执行记录
--
-- 注意：
--   1. t_agent_* 为新建表，使用 CREATE TABLE IF NOT EXISTS。
--   2. 第 8 节会为已存在的 t_knowledge_retrieval_log 补 Trace 字段和索引。
--      该 ALTER 不完全幂等，重复执行前必须先确认字段和索引不存在。
--   3. 不创建跨业务库外键，所有业务单号只保存脱敏编号，由应用层校验归属。
--   4. 不保存 JWT、密码、卡密明文、支付凭证、完整身份证、银行卡和异常堆栈。
-- =====================================================================================

CREATE DATABASE IF NOT EXISTS `db_agent` DEFAULT CHARACTER SET utf8mb4;

USE `db_agent`;

-- -------------------------------------------------------------------------------------
-- 1. 客服会话表
-- -------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_agent_session` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `session_no` varchar(64) NOT NULL,
    `agent_type` varchar(32) NOT NULL DEFAULT 'CUSTOMER_SERVICE' COMMENT '本脚本只服务 CUSTOMER_SERVICE',
    `user_id` bigint unsigned NOT NULL,
    `user_role` varchar(16) NOT NULL COMMENT 'USER, ADMIN',
    `status` varchar(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE, HANDOFF, CLOSED, EXPIRED',
    `last_intent` varchar(64) DEFAULT NULL COMMENT '最近一次主意图',
    `active_order_no` varchar(64) DEFAULT NULL COMMENT '当前已确认订单号',
    `active_dispute_no` varchar(64) DEFAULT NULL COMMENT '当前已确认售后单号',
    `slot_json` json DEFAULT NULL COMMENT '已确认槽位，只保存业务必要字段',
    `summary` varchar(2048) DEFAULT NULL COMMENT '滚动摘要，不含敏感信息',
    `context_json` json DEFAULT NULL COMMENT '客服上下文快照，不含原始提示词全文',
    `message_count` int NOT NULL DEFAULT 0,
    `last_active_time` datetime(3) NOT NULL,
    `closed_time` datetime(3) DEFAULT NULL,
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_session_no` (`session_no`),
    KEY `idx_user_type_time` (`user_id`, `agent_type`, `last_active_time`),
    KEY `idx_status_time` (`status`, `last_active_time`),
    KEY `idx_active_order` (`active_order_no`),
    KEY `idx_active_dispute` (`active_dispute_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='智能客服会话表';

-- -------------------------------------------------------------------------------------
-- 2. 客服消息表
-- client_message_id 仅用户消息必填，用于同一 session 内幂等。
-- -------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_agent_message` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `message_no` varchar(64) NOT NULL,
    `session_no` varchar(64) NOT NULL,
    `run_no` varchar(64) DEFAULT NULL,
    `client_message_id` varchar(64) DEFAULT NULL COMMENT '前端幂等 ID，仅用户消息填写',
    `role` varchar(16) NOT NULL COMMENT 'USER, AGENT, SYSTEM_SUMMARY',
    `content` mediumtext NOT NULL,
    `citations_json` json DEFAULT NULL COMMENT '知识引用编号、文档、版本、小节',
    `tool_evidence_json` json DEFAULT NULL COMMENT '订单或售后事实来源摘要',
    `token_count` int DEFAULT NULL,
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_message_no` (`message_no`),
    UNIQUE KEY `uk_session_client_message` (`session_no`, `client_message_id`),
    KEY `idx_session_time` (`session_no`, `created_time`),
    KEY `idx_run_no` (`run_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='智能客服消息表';

-- -------------------------------------------------------------------------------------
-- 3. Agent 运行表
-- 记录一次 Graph 运行的模型、路径、工具统计和结构化中间结果。
-- -------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_agent_run` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `run_no` varchar(64) NOT NULL,
    `session_no` varchar(64) NOT NULL,
    `agent_type` varchar(32) NOT NULL DEFAULT 'CUSTOMER_SERVICE',
    `trigger_type` varchar(24) NOT NULL DEFAULT 'CHAT' COMMENT 'CHAT, EVALUATION, REPLAY',
    `biz_type` varchar(32) DEFAULT NULL COMMENT 'ORDER, DISPUTE, ITEM',
    `biz_no` varchar(64) DEFAULT NULL,
    `status` varchar(24) NOT NULL DEFAULT 'CREATED' COMMENT 'CREATED, RUNNING, WAITING_TOOL, SUCCEEDED, FAILED, TIMEOUT, CANCELED',
    `trace_id` varchar(64) DEFAULT NULL,
    `request_hash` char(64) NOT NULL,
    `prompt_version` varchar(32) NOT NULL,
    `provider` varchar(24) NOT NULL COMMENT 'MOCK, DASHSCOPE',
    `model_name` varchar(64) DEFAULT NULL,
    `master_model_name` varchar(64) DEFAULT NULL,
    `retrieval_mode` varchar(16) NOT NULL DEFAULT 'VECTOR' COMMENT 'VECTOR, KEYWORD, HYBRID, DEGRADED',
    `embedding_cache_hit` tinyint NOT NULL DEFAULT 0 COMMENT 'Embedding 是否命中缓存',
    `knowledge_chunk_ids_json` json DEFAULT NULL COMMENT '最终引用或命中的父片 ID',
    `master_result_json` json DEFAULT NULL COMMENT 'MasterAgent 意图识别与重写结果',
    `specialist_results_json` json DEFAULT NULL COMMENT '子 Agent 输出集合',
    `graph_path_json` json DEFAULT NULL COMMENT 'Graph 节点路径',
    `tool_rounds` int NOT NULL DEFAULT 0,
    `tool_call_count` int NOT NULL DEFAULT 0,
    `input_tokens` int DEFAULT NULL,
    `output_tokens` int DEFAULT NULL,
    `latency_ms` int DEFAULT NULL,
    `ttft_ms` int DEFAULT NULL COMMENT '首字响应耗时(ms)',
    `error_code` varchar(64) DEFAULT NULL,
    `error_message` varchar(512) DEFAULT NULL COMMENT '脱敏错误摘要',
    `result_json` json DEFAULT NULL COMMENT '最终响应',
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `finished_time` datetime(3) DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_run_no` (`run_no`),
    KEY `idx_session_time` (`session_no`, `created_time`),
    KEY `idx_agent_status_time` (`agent_type`, `status`, `created_time`),
    KEY `idx_biz` (`biz_type`, `biz_no`),
    KEY `idx_trace_id` (`trace_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='智能客服运行表';

-- -------------------------------------------------------------------------------------
-- 4. 工具调用表
-- input_json / output_json 由应用层做字段白名单和长度截断后再落库。
-- -------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_agent_tool_call` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `call_no` varchar(64) NOT NULL,
    `run_no` varchar(64) NOT NULL,
    `session_no` varchar(64) NOT NULL,
    `agent_name` varchar(32) NOT NULL COMMENT 'RULE_AGENT, AFTER_SALE_AGENT 等',
    `tool_name` varchar(64) NOT NULL COMMENT 'searchKnowledge, queryOrder 等',
    `call_sequence` int NOT NULL,
    `round_no` int NOT NULL DEFAULT 1,
    `input_json` json NOT NULL,
    `output_json` json DEFAULT NULL,
    `allowed` tinyint NOT NULL DEFAULT 1 COMMENT '是否通过白名单校验',
    `success` tinyint NOT NULL DEFAULT 0,
    `error_code` varchar(64) DEFAULT NULL,
    `error_message` varchar(512) DEFAULT NULL COMMENT '脱敏错误摘要',
    `elapsed_ms` int NOT NULL DEFAULT 0,
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_call_no` (`call_no`),
    KEY `idx_run_seq` (`run_no`, `call_sequence`),
    KEY `idx_session_time` (`session_no`, `created_time`),
    KEY `idx_tool_time` (`tool_name`, `created_time`),
    KEY `idx_agent_tool_time` (`agent_name`, `tool_name`, `created_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='智能客服工具调用记录表';

-- -------------------------------------------------------------------------------------
-- 5. 转人工表
-- Agent 只写转人工记录，不写业务工单，不修改订单和售后状态。
-- -------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_agent_handoff` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `handoff_no` varchar(64) NOT NULL,
    `session_no` varchar(64) NOT NULL,
    `run_no` varchar(64) NOT NULL,
    `user_id` bigint unsigned NOT NULL,
    `target_role` varchar(24) NOT NULL DEFAULT 'CUSTOMER_SERVICE' COMMENT '本期仅 CUSTOMER_SERVICE',
    `reason` varchar(255) NOT NULL,
    `priority` varchar(16) NOT NULL DEFAULT 'NORMAL' COMMENT 'LOW, NORMAL, HIGH, URGENT',
    `biz_type` varchar(32) DEFAULT NULL,
    `biz_no` varchar(64) DEFAULT NULL,
    `context_json` json DEFAULT NULL COMMENT '诉求、订单/售后摘要、已尝试回答',
    `status` varchar(16) NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN, PROCESSING, RESOLVED, CLOSED',
    `handler_id` bigint unsigned DEFAULT NULL,
    `claimed_time` datetime(3) DEFAULT NULL,
    `resolved_time` datetime(3) DEFAULT NULL,
    `resolution` varchar(512) DEFAULT NULL,
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_handoff_no` (`handoff_no`),
    KEY `idx_status_priority_time` (`status`, `priority`, `created_time`),
    KEY `idx_user_time` (`user_id`, `created_time`),
    KEY `idx_session_time` (`session_no`, `created_time`),
    KEY `idx_handler_status` (`handler_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='智能客服转人工记录表';

-- -------------------------------------------------------------------------------------
-- 6. 用户反馈表
-- -------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_agent_feedback` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `session_no` varchar(64) NOT NULL,
    `run_no` varchar(64) NOT NULL,
    `user_id` bigint unsigned NOT NULL,
    `rating` tinyint NOT NULL COMMENT '1-5',
    `reason_codes_json` json DEFAULT NULL COMMENT '不解决、答案错误、响应慢等枚举',
    `comment` varchar(512) DEFAULT NULL,
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_run_user` (`run_no`, `user_id`),
    KEY `idx_session_time` (`session_no`, `created_time`),
    KEY `idx_user_time` (`user_id`, `created_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='智能客服用户反馈表';

-- -------------------------------------------------------------------------------------
-- 7. 固定评估用例与结果
-- 评估数据不复制真实用户隐私，只保存测试输入和期望结果。
-- -------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_agent_eval_case` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `case_no` varchar(64) NOT NULL,
    `agent_type` varchar(32) NOT NULL DEFAULT 'CUSTOMER_SERVICE',
    `actor_role` varchar(16) NOT NULL COMMENT 'USER, ADMIN',
    `intent` varchar(64) DEFAULT NULL,
    `input_json` json NOT NULL,
    `expected_tools_json` json NOT NULL,
    `expected_points_json` json NOT NULL,
    `forbidden_content_json` json NOT NULL,
    `enabled` tinyint NOT NULL DEFAULT 1,
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_case_no` (`case_no`),
    KEY `idx_agent_intent_enabled` (`agent_type`, `intent`, `enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='智能客服评估用例表';

CREATE TABLE IF NOT EXISTS `t_agent_eval_result` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `eval_batch_no` varchar(64) NOT NULL,
    `case_id` bigint unsigned NOT NULL,
    `run_no` varchar(64) NOT NULL,
    `passed` tinyint NOT NULL DEFAULT 0,
    `score` int NOT NULL DEFAULT 0,
    `metrics_json` json NOT NULL,
    `failure_reason` varchar(512) DEFAULT NULL,
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_batch_case` (`eval_batch_no`, `case_id`),
    KEY `idx_run_no` (`run_no`),
    KEY `idx_batch_passed` (`eval_batch_no`, `passed`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='智能客服评估结果表';

-- -------------------------------------------------------------------------------------
-- 8. RAG 检索日志 Trace 扩展
-- 该表由 stage3_rag_schema.sql 创建。以下 ALTER 用于关联客服 run、session 和工具调用。
-- 重复执行前必须先确认字段和索引不存在。
-- -------------------------------------------------------------------------------------
ALTER TABLE `t_knowledge_retrieval_log`
    ADD COLUMN `run_no` varchar(64) DEFAULT NULL COMMENT 'Agent run 编号' AFTER `log_no`,
    ADD COLUMN `session_no` varchar(64) DEFAULT NULL COMMENT 'Agent session 编号' AFTER `run_no`,
    ADD COLUMN `tool_call_no` varchar(64) DEFAULT NULL COMMENT '工具调用编号' AFTER `session_no`,
    ADD COLUMN `agent_name` varchar(32) DEFAULT NULL COMMENT '发起检索的子 Agent' AFTER `tool_call_no`,
    ADD KEY `idx_run_no` (`run_no`),
    ADD KEY `idx_session_time` (`session_no`, `created_time`),
    ADD KEY `idx_tool_call_no` (`tool_call_no`);

-- =====================================================================================
-- 9. 执行后校验
-- =====================================================================================
-- SHOW TABLES LIKE 't_agent%';
-- SHOW CREATE TABLE db_agent.t_agent_session;
-- SHOW CREATE TABLE db_agent.t_agent_run;
-- SHOW CREATE TABLE db_agent.t_agent_tool_call;
-- SHOW CREATE TABLE db_agent.t_knowledge_retrieval_log;
--
-- 检查用户消息幂等索引：
-- SHOW INDEX FROM db_agent.t_agent_message WHERE Key_name = 'uk_session_client_message';
--
-- 检查检索日志扩展字段：
-- SELECT column_name, column_type, is_nullable
--   FROM information_schema.columns
--  WHERE table_schema = 'db_agent'
--    AND table_name = 't_knowledge_retrieval_log'
--    AND column_name IN ('run_no', 'session_no', 'tool_call_no', 'agent_name');
