-- =====================================================================================
-- 阶段三智能客服助手 —— 长期偏好记忆 MySQL 迁移脚本
-- -------------------------------------------------------------------------------------
-- 对应设计文档：doc/agent/customerServiceAgent/mem0_long_term_memory_design.md
-- 目标库：db_agent
-- 数据库版本：MySQL 8.0
-- 执行顺序：在已存在 t_agent_session, t_agent_message, t_agent_run 的基础上执行
-- =====================================================================================

USE `db_agent`;

-- -------------------------------------------------------------------------------------
-- 1. 用户记忆全局设置表
-- -------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_agent_memory_setting` (
    `user_id` bigint unsigned NOT NULL COMMENT '用户ID，主键',
    `subject_key` varchar(64) NOT NULL COMMENT '稳定主体唯一标识 (u_UUID)',
    `read_enabled` tinyint NOT NULL DEFAULT 0 COMMENT '回答是否允许使用记忆',
    `write_enabled` tinyint NOT NULL DEFAULT 0 COMMENT '是否允许自动采集新记忆',
    `epoch` bigint unsigned NOT NULL DEFAULT 1 COMMENT '授权代际，关闭/清空时递增',
    `revision` bigint unsigned NOT NULL DEFAULT 1 COMMENT '记忆集合版本，发生任何变更递增',
    `policy_version` varchar(32) NOT NULL DEFAULT 'v1' COMMENT '用户签署的产品隐私协议版本',
    `enabled_at` datetime(3) DEFAULT NULL COMMENT '首次或最新开启时间',
    `capture_after_sequence` bigint unsigned NOT NULL DEFAULT 0 COMMENT '允许采集的来源下界',
    `next_source_sequence` bigint unsigned NOT NULL DEFAULT 1 COMMENT '每个主体的单调事件序号分配器',
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`user_id`),
    UNIQUE KEY `uk_subject_key` (`subject_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户长期偏好记忆设置表';

-- -------------------------------------------------------------------------------------
-- 2. 偏好记忆当前指针表 (Head)
-- 保证每个 (user_id, preference_key) 在 ACTIVE 状态下的唯一性与版本追踪
-- -------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_agent_memory_head` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `user_id` bigint unsigned NOT NULL COMMENT '用户ID',
    `preference_key` varchar(128) NOT NULL COMMENT '偏好维度唯一键 (类型+范围+排他维度)',
    `logical_id` varchar(64) NOT NULL COMMENT '逻辑偏好标识',
    `current_memory_no` varchar(64) NOT NULL COMMENT '当前指向的有效 memory_no',
    `current_version` int unsigned NOT NULL DEFAULT 1 COMMENT '当前有效版本号',
    `epoch` bigint unsigned NOT NULL DEFAULT 1 COMMENT '记录所处的授权代际',
    `last_source_sequence` bigint unsigned NOT NULL DEFAULT 0 COMMENT '最后一次确认时的来源序列号',
    `blocked_through_sequence` bigint unsigned NOT NULL DEFAULT 0 COMMENT '删除屏障序列号，阻断迟到写入',
    `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '是否已被删除 (0=正常, 1=已删除)',
    `row_version` bigint unsigned NOT NULL DEFAULT 1 COMMENT '乐观锁版本',
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_pref_key` (`user_id`, `preference_key`),
    KEY `idx_user_epoch_deleted` (`user_id`, `epoch`, `deleted`),
    KEY `idx_logical_id` (`logical_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户偏好记忆当前指针表';

-- -------------------------------------------------------------------------------------
-- 3. 偏好记忆版本记录表 (Record)
-- 保存不可变的多版本偏好明细
-- -------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_agent_memory_record` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `memory_no` varchar(64) NOT NULL COMMENT '对外随机业务编号 CM_xxx',
    `logical_id` varchar(64) NOT NULL COMMENT '逻辑偏好标识 CL_xxx',
    `version` int unsigned NOT NULL DEFAULT 1 COMMENT '版本号',
    `user_id` bigint unsigned NOT NULL COMMENT '用户ID',
    `subject_key` varchar(64) NOT NULL COMMENT '主体标识',
    `epoch` bigint unsigned NOT NULL DEFAULT 1 COMMENT '所属授权代际',
    `memory_type` varchar(32) NOT NULL COMMENT '偏好类型',
    `preference_key` varchar(128) NOT NULL COMMENT '偏好维度唯一键',
    `scope_json` json NOT NULL COMMENT '适用范围对象',
    `value_json` json NOT NULL COMMENT '偏好具体值对象',
    `polarity` varchar(16) NOT NULL DEFAULT 'PREFER' COMMENT 'PREFER / EXCLUDE',
    `canonical_text` varchar(512) NOT NULL COMMENT '规范陈述文本',
    `source_event_id` varchar(64) DEFAULT NULL COMMENT '来源事件ID',
    `source_message_no` varchar(64) DEFAULT NULL COMMENT '来源用户消息号',
    `source_session_no` varchar(64) DEFAULT NULL COMMENT '来源会话号',
    `source_run_no` varchar(64) DEFAULT NULL COMMENT '来源运行号',
    `source_sequence` bigint unsigned NOT NULL DEFAULT 0 COMMENT '来源分配序列号',
    `evidence_hash` char(64) DEFAULT NULL COMMENT '原句证据SHA256',
    `evidence_text` varchar(512) DEFAULT NULL COMMENT '原句脱敏证据',
    `evidence_kind` varchar(32) NOT NULL DEFAULT 'EXPLICIT_USER_STATEMENT',
    `confidence` decimal(5,4) NOT NULL DEFAULT 1.0000 COMMENT '置信度',
    `confirmation_count` int unsigned NOT NULL DEFAULT 1 COMMENT '累计确认次数',
    `schema_version` varchar(32) NOT NULL DEFAULT 'preference-v1',
    `extractor_version` varchar(32) NOT NULL DEFAULT 'extractor-v1',
    `status` varchar(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE, SUPERSEDED, EXPIRED, DELETED',
    `observed_at` datetime(3) NOT NULL COMMENT '首次观察时间',
    `last_confirmed_at` datetime(3) NOT NULL COMMENT '最近一次确认时间',
    `expires_at` datetime(3) NOT NULL COMMENT '失效时间',
    `superseded_by` varchar(64) DEFAULT NULL COMMENT '被哪个 memory_no 替换',
    `deleted_at` datetime(3) DEFAULT NULL COMMENT '逻辑删除时间',
    `row_version` bigint unsigned NOT NULL DEFAULT 1 COMMENT '乐观锁版本',
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_memory_no` (`memory_no`),
    UNIQUE KEY `uk_user_logical_ver` (`user_id`, `logical_id`, `version`),
    UNIQUE KEY `uk_source_event_key` (`source_event_id`, `preference_key`),
    KEY `idx_user_query` (`user_id`, `epoch`, `status`, `expires_at`),
    KEY `idx_source_msg` (`source_message_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户偏好记忆版本记录表';

-- -------------------------------------------------------------------------------------
-- 4. 记忆异步任务 Outbox 表
-- 实现对话主流程事务提交与后台异步抽取解耦
-- -------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_agent_memory_outbox` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `event_id` varchar(64) NOT NULL COMMENT '事件ID，如 EV_xxx',
    `event_type` varchar(32) NOT NULL COMMENT 'CAPTURE_TURN, PURGE_RECORD, PURGE_SCOPE',
    `dedupe_key` varchar(128) NOT NULL COMMENT '幂等去重键',
    `user_id` bigint unsigned NOT NULL COMMENT '用户ID',
    `subject_key` varchar(64) NOT NULL COMMENT '主体标识',
    `epoch` bigint unsigned NOT NULL DEFAULT 1 COMMENT '事件产生时的 epoch',
    `source_sequence` bigint unsigned NOT NULL DEFAULT 0 COMMENT '来源序列',
    `payload_json` json NOT NULL COMMENT '不可变事件载荷 (引用信息/待删ID集合)',
    `state` varchar(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING, PROCESSING, SUCCEEDED, RETRY_WAIT, DEAD, SKIPPED',
    `attempt_count` int unsigned NOT NULL DEFAULT 0 COMMENT '已尝试次数',
    `next_attempt_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '下次可尝试时间',
    `lease_owner` varchar(64) DEFAULT NULL COMMENT '当前持有租约的 Worker 实例',
    `lease_generation` bigint unsigned NOT NULL DEFAULT 0 COMMENT '租约代次 (CAS防脑裂)',
    `lease_until` datetime(3) DEFAULT NULL COMMENT '租约过期时间',
    `last_error_code` varchar(64) DEFAULT NULL COMMENT '最后错误码',
    `last_error_message` varchar(512) DEFAULT NULL COMMENT '错误摘要',
    `trace_id` varchar(64) DEFAULT NULL,
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `finished_time` datetime(3) DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_event_id` (`event_id`),
    UNIQUE KEY `uk_dedupe_key` (`dedupe_key`),
    KEY `idx_claim` (`state`, `next_attempt_at`, `id`),
    KEY `idx_user_seq` (`user_id`, `source_sequence`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='记忆异步任务发件箱表';

-- -------------------------------------------------------------------------------------
-- 5. 记忆审计日志表
-- -------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_agent_memory_audit` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `audit_no` varchar(64) NOT NULL COMMENT '审计编号',
    `event_id` varchar(64) DEFAULT NULL COMMENT '关联 Outbox 事件ID',
    `memory_no` varchar(64) DEFAULT NULL COMMENT '关联偏好编号',
    `logical_id` varchar(64) DEFAULT NULL,
    `version` int unsigned DEFAULT NULL,
    `user_id` bigint unsigned NOT NULL,
    `action` varchar(32) NOT NULL COMMENT 'CREATE, UPDATE, DELETE, CLEAR, EXPIRE',
    `result` varchar(16) NOT NULL COMMENT 'SUCCESS, REJECTED, SKIPPED, FAILED',
    `detail_json` json DEFAULT NULL COMMENT '审计详细元数据',
    `run_no` varchar(64) DEFAULT NULL,
    `trace_id` varchar(64) DEFAULT NULL,
    `operator_type` varchar(16) NOT NULL DEFAULT 'SYSTEM' COMMENT 'USER, SYSTEM, ADMIN',
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_audit_no` (`audit_no`),
    KEY `idx_user_action_time` (`user_id`, `action`, `created_time`),
    KEY `idx_memory_no` (`memory_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='记忆操作审计表';

-- -------------------------------------------------------------------------------------
-- 6. 记忆管理操作幂等表
-- -------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_agent_memory_operation` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `operation_no` varchar(64) NOT NULL COMMENT '操作流水号 MO_xxx',
    `user_id` bigint unsigned NOT NULL,
    `client_operation_id` varchar(64) NOT NULL COMMENT '客户端提供的 Idempotency-Key 或服务端派生键',
    `request_hash` char(64) NOT NULL COMMENT '请求载荷哈希',
    `operation_type` varchar(32) NOT NULL COMMENT 'UPDATE_SETTINGS, PATCH_MEMORY, DELETE_MEMORY, CLEAR_ALL',
    `target_scope` varchar(128) DEFAULT NULL COMMENT '目标范围描述',
    `effective_state` varchar(16) NOT NULL COMMENT 'FORGOTTEN, UPDATED, COMPLETED',
    `purge_state` varchar(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING, COMPLETED',
    `source_sequence` bigint unsigned NOT NULL DEFAULT 0,
    `epoch` bigint unsigned NOT NULL DEFAULT 1,
    `revision` bigint unsigned NOT NULL DEFAULT 1,
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `finished_time` datetime(3) DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_operation_no` (`operation_no`),
    UNIQUE KEY `uk_user_client_op` (`user_id`, `client_operation_id`),
    KEY `idx_user_time` (`user_id`, `created_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='记忆管理操作幂等记录表';

-- -------------------------------------------------------------------------------------
-- 7. 范围遗忘屏障表 (Scope Barrier)
-- 防止针对某个分类/范围删除后，在途异步任务将该范围内的新 key 写回
-- -------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_agent_memory_scope_barrier` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `user_id` bigint unsigned NOT NULL,
    `normalized_scope_key` varchar(128) NOT NULL COMMENT '规范化的范围Key，例如 BENEFICIARY:SELF|CAT:GAME_TOPUP',
    `scope_json` json NOT NULL,
    `blocked_through_sequence` bigint unsigned NOT NULL DEFAULT 0 COMMENT '在此序列及以前产生的信息不得入库',
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_scope_key` (`user_id`, `normalized_scope_key`),
    KEY `idx_user_time` (`user_id`, `created_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='范围遗忘屏障表';

-- -------------------------------------------------------------------------------------
-- 8. 扩展 t_agent_run 运行表
-- 增加记忆采集上下文与使用证据列
-- -------------------------------------------------------------------------------------
SET @exist_col := (
    SELECT COUNT(*) FROM information_schema.columns 
    WHERE table_schema = DATABASE() AND table_name = 't_agent_run' AND column_name = 'memory_capture_context_json'
);
SET @sql_stmt := IF(@exist_col = 0,
    'ALTER TABLE `t_agent_run` ADD COLUMN `memory_capture_context_json` json DEFAULT NULL COMMENT ''记忆采集上下文(授权、序列、策略)'' AFTER `graph_path_json`',
    'SELECT "column memory_capture_context_json already exists"');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exist_col := (
    SELECT COUNT(*) FROM information_schema.columns 
    WHERE table_schema = DATABASE() AND table_name = 't_agent_run' AND column_name = 'memory_evidence_json'
);
SET @sql_stmt := IF(@exist_col = 0,
    'ALTER TABLE `t_agent_run` ADD COLUMN `memory_evidence_json` json DEFAULT NULL COMMENT ''记忆召回与注入证据快照'' AFTER `memory_capture_context_json`',
    'SELECT "column memory_evidence_json already exists"');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exist_col := (
    SELECT COUNT(*) FROM information_schema.columns 
    WHERE table_schema = DATABASE() AND table_name = 't_agent_run' AND column_name = 'run_implementation_sha256'
);
SET @sql_stmt := IF(@exist_col = 0,
    'ALTER TABLE `t_agent_run` ADD COLUMN `run_implementation_sha256` varchar(64) DEFAULT NULL COMMENT ''运行时实现指纹'' AFTER `memory_evidence_json`',
    'SELECT "column run_implementation_sha256 already exists"');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exist_col := (
    SELECT COUNT(*) FROM information_schema.columns 
    WHERE table_schema = DATABASE() AND table_name = 't_agent_run' AND column_name = 'run_memory_manifest'
);
SET @sql_stmt := IF(@exist_col = 0,
    'ALTER TABLE `t_agent_run` ADD COLUMN `run_memory_manifest` json DEFAULT NULL COMMENT ''运行时记忆模块配置与模型清单'' AFTER `run_implementation_sha256`',
    'SELECT "column run_memory_manifest already exists"');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;
