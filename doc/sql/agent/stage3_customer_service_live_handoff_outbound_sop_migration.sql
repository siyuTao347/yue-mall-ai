-- =====================================================================================
-- 阶段三智能客服助手 —— 实时人工协同、主动触达、SOP 与富媒体卡片 MySQL 增量迁移脚本
-- -------------------------------------------------------------------------------------
-- 对应设计文档：doc/agent/customerServiceAgent/live_handoff_outbound_sop_rich_ui_design.md
-- 目标库：db_agent
-- 数据库版本：MySQL 8.0+
-- 执行顺序：在 stage3_customer_service_agent_schema.sql 和
--           stage3_customer_service_memory_mysql_migration.sql 执行之后执行
-- 说明：未接入 Flyway / Liquibase，发布前按环境规范执行并留存记录
-- =====================================================================================

USE `db_agent`;

-- =====================================================================================
-- 1. 现有表字段与索引扩展 (Section 10.1)
-- =====================================================================================

-- 1.1 t_agent_session 扩展
ALTER TABLE `t_agent_session`
    ADD COLUMN `service_mode` varchar(16) NOT NULL DEFAULT 'BOT' COMMENT 'BOT, QUEUED, HUMAN, NONE' AFTER `status`,
    ADD COLUMN `origin` varchar(16) NOT NULL DEFAULT 'USER_CHAT' COMMENT 'USER_CHAT, OUTBOUND, SYSTEM' AFTER `service_mode`,
    ADD COLUMN `control_epoch` bigint unsigned NOT NULL DEFAULT 1 COMMENT '控制权代际，转接/认领/转派/退回递增' AFTER `origin`,
    ADD COLUMN `version` bigint unsigned NOT NULL DEFAULT 1 COMMENT '乐观锁版本' AFTER `control_epoch`,
    ADD COLUMN `last_event_seq` bigint unsigned NOT NULL DEFAULT 0 COMMENT '当前已分配最大公开事件序列号' AFTER `version`,
    ADD COLUMN `last_public_message_seq` bigint unsigned NOT NULL DEFAULT 0 COMMENT '最新公开消息序号' AFTER `last_event_seq`,
    ADD COLUMN `active_handoff_no` varchar(64) DEFAULT NULL COMMENT '当前处于 OPEN/PROCESSING 的转接单号' AFTER `last_public_message_seq`,
    ADD COLUMN `assigned_staff_id` bigint unsigned DEFAULT NULL COMMENT '当前负责坐席 staff_id' AFTER `active_handoff_no`,
    ADD COLUMN `business_type` varchar(32) DEFAULT NULL COMMENT '关联主业务类型 ORDER, DISPUTE' AFTER `assigned_staff_id`,
    ADD COLUMN `business_no` varchar(64) DEFAULT NULL COMMENT '关联主业务单号' AFTER `business_type`,
    ADD COLUMN `predecessor_session_no` varchar(64) DEFAULT NULL COMMENT '前序会话号，用于主动触达新建会话追溯' AFTER `business_no`,
    ADD COLUMN `last_user_active_time` datetime(3) DEFAULT NULL COMMENT '最后一次用户真实主动发言时间' AFTER `last_active_time`,
    ADD COLUMN `summary_version` bigint unsigned NOT NULL DEFAULT 0 COMMENT '当前摘要版本' AFTER `summary`,
    ADD COLUMN `summary_covered_message_seq` bigint unsigned NOT NULL DEFAULT 0 COMMENT '当前摘要已覆盖的消息序号水位' AFTER `summary_version`,
    ADD KEY `idx_user_updated` (`user_id`, `updated_time`, `id`),
    ADD KEY `idx_assigned_staff_mode` (`assigned_staff_id`, `service_mode`),
    ADD KEY `idx_active_handoff` (`active_handoff_no`);

-- 1.2 t_agent_message 扩展
ALTER TABLE `t_agent_message`
    ADD COLUMN `session_seq` bigint unsigned NOT NULL DEFAULT 0 COMMENT '会话内连续公开事件序号' AFTER `session_no`,
    ADD COLUMN `sender_type` varchar(16) NOT NULL DEFAULT 'USER' COMMENT 'USER, AI, STAFF, OUTBOUND, SOP, SYSTEM' AFTER `role`,
    ADD COLUMN `sender_id` varchar(64) DEFAULT NULL COMMENT '发送人标识 (userId, staffId, agentType)' AFTER `sender_type`,
    ADD COLUMN `payload_json` json DEFAULT NULL COMMENT '消息正文结构与版本化卡片集合' AFTER `content`,
    ADD COLUMN `schema_version` int NOT NULL DEFAULT 2 COMMENT '协议结构版本 2=旧版, 3=富媒体卡片' AFTER `payload_json`,
    ADD COLUMN `origin_event_id` varchar(64) DEFAULT NULL COMMENT '引发该消息的外部或系统事件 ID' AFTER `schema_version`,
    ADD COLUMN `workflow_no` varchar(64) DEFAULT NULL COMMENT '关联的 SOP 流程实例编号' AFTER `origin_event_id`,
    ADD COLUMN `copilot_task_no` varchar(64) DEFAULT NULL COMMENT '采用的 Copilot 建议任务编号' AFTER `workflow_no`,
    ADD COLUMN `output_kind` varchar(16) NOT NULL DEFAULT 'FINAL' COMMENT 'FINAL, DELTA, PREVIEW' AFTER `copilot_task_no`,
    ADD COLUMN `request_hash` char(64) DEFAULT NULL COMMENT '发送请求业务语义哈希' AFTER `output_kind`,
    ADD KEY `idx_session_seq` (`session_no`, `session_seq`),
    ADD KEY `idx_run_output_kind` (`run_no`, `output_kind`);

-- 1.3 t_agent_handoff 扩展
-- 先增加字段与新索引
ALTER TABLE `t_agent_handoff`
    ADD COLUMN `risk_level` varchar(16) NOT NULL DEFAULT 'NORMAL' COMMENT 'LOW, NORMAL, HIGH, URGENT' AFTER `priority`,
    ADD COLUMN `risk_sources_json` json DEFAULT NULL COMMENT '风险判定来源与命中证据' AFTER `risk_level`,
    ADD COLUMN `skill_group_id` bigint unsigned DEFAULT NULL COMMENT '目标技能组 ID' AFTER `target_role`,
    ADD COLUMN `routing_rule_version` varchar(32) DEFAULT NULL COMMENT '生效的路由规则编码及版本' AFTER `skill_group_id`,
    ADD COLUMN `routing_snapshot_json` json DEFAULT NULL COMMENT '路由决策过程审计快照' AFTER `routing_rule_version`,
    ADD COLUMN `priority_rank` int NOT NULL DEFAULT 100 COMMENT '队列排序优先级，数值越小越优先' AFTER `priority`,
    ADD COLUMN `version` bigint unsigned NOT NULL DEFAULT 1 COMMENT '乐观锁版本' AFTER `priority_rank`,
    ADD COLUMN `queued_time` datetime(3) DEFAULT NULL COMMENT '进入排队队列的时间' AFTER `created_time`,
    ADD COLUMN `sla_due_time` datetime(3) DEFAULT NULL COMMENT '首响 SLA 到期时间' AFTER `queued_time`,
    ADD COLUMN `end_disposition` varchar(32) DEFAULT NULL COMMENT '结束处理动作 CLOSE, RETURN_TO_BOT' AFTER `resolution`,
    ADD COLUMN `last_staff_seen_time` datetime(3) DEFAULT NULL COMMENT '坐席最后一次查看时间' AFTER `claimed_time`,
    ADD COLUMN `active_session_no` varchar(64) GENERATED ALWAYS AS (IF(`status` IN ('OPEN','PROCESSING'), `session_no`, NULL)) VIRTUAL,
    ADD KEY `idx_status_group_prio` (`status`, `skill_group_id`, `priority_rank`, `queued_time`, `id`),
    ADD UNIQUE KEY `uk_active_session` (`active_session_no`);

-- 修改 run_no 为可空（支持事件或用户直接触发人工，不强依赖 AI Run）
ALTER TABLE `t_agent_handoff` MODIFY COLUMN `run_no` varchar(64) DEFAULT NULL;

-- 1.4 t_agent_run 扩展
ALTER TABLE `t_agent_run`
    ADD COLUMN `source_message_no` varchar(64) DEFAULT NULL COMMENT '触发此次运行的原始用户消息编号' AFTER `session_no`,
    ADD COLUMN `control_epoch` bigint unsigned NOT NULL DEFAULT 1 COMMENT '启动时认领的会话控制权代际' AFTER `source_message_no`,
    ADD COLUMN `operator_id` varchar(64) DEFAULT NULL COMMENT '发起者标识 (用户ID或坐席ID)' AFTER `control_epoch`,
    ADD COLUMN `response_visibility` varchar(16) NOT NULL DEFAULT 'PUBLIC' COMMENT 'PUBLIC=用户可见, INTERNAL=内部辅助' AFTER `operator_id`,
    ADD COLUMN `lease_owner` varchar(64) DEFAULT NULL COMMENT '当前持有执行租约的 Worker 实例' AFTER `status`,
    ADD COLUMN `lease_until` datetime(3) DEFAULT NULL COMMENT '租约截止时间' AFTER `lease_owner`,
    ADD COLUMN `lease_generation` bigint unsigned NOT NULL DEFAULT 0 COMMENT '租约代际，防止失效 Worker 迟到提交' AFTER `lease_until`,
    ADD COLUMN `attempt_count` int NOT NULL DEFAULT 0 COMMENT '重试或重算次数' AFTER `lease_generation`,
    ADD COLUMN `next_retry_time` datetime(3) DEFAULT NULL COMMENT '下一次调度或重试时间' AFTER `attempt_count`,
    ADD COLUMN `context_event_seq` bigint unsigned NOT NULL DEFAULT 0 COMMENT '推理时采纳的会话最新事件序号' AFTER `next_retry_time`,
    ADD KEY `idx_status_retry` (`status`, `next_retry_time`, `id`),
    ADD KEY `idx_source_message` (`source_message_no`);

-- 1.5 t_agent_feedback 扩展
ALTER TABLE `t_agent_feedback`
    ADD COLUMN `feedback_scope` varchar(16) NOT NULL DEFAULT 'RUN' COMMENT 'RUN, HANDOFF, WORKFLOW' AFTER `session_no`,
    ADD COLUMN `target_no` varchar(64) NOT NULL DEFAULT '' COMMENT '评价目标编号 (runNo, handoffNo, workflowNo)' AFTER `feedback_scope`,
    ADD COLUMN `version` bigint unsigned NOT NULL DEFAULT 1 COMMENT '更新版本' AFTER `rating`,
    MODIFY COLUMN `run_no` varchar(64) DEFAULT NULL,
    ADD KEY `idx_user_scope_target` (`user_id`, `feedback_scope`, `target_no`);


-- =====================================================================================
-- 2. 统一消息与可靠通信表 (Section 10.2)
-- =====================================================================================

-- 2.1 会话公开事件序列持久表（客户端增量 Sync 的权威数据源）
CREATE TABLE IF NOT EXISTS `t_agent_session_event` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `event_id` varchar(64) NOT NULL COMMENT '事件全局唯一 ID (EV_xxx)',
    `session_no` varchar(64) NOT NULL COMMENT '会话编号',
    `event_seq` bigint unsigned NOT NULL COMMENT '会话内严格单调自增序号 (1, 2, 3...)',
    `event_type` varchar(64) NOT NULL COMMENT 'message.created, session.mode.changed, handoff.changed, card.updated 等',
    `public_payload_json` json NOT NULL COMMENT '用户端可见的脱敏事件载荷',
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_event_id` (`event_id`),
    UNIQUE KEY `uk_session_seq` (`session_no`, `event_seq`),
    KEY `idx_created_time` (`created_time`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='会话公开事件序列总线表';

-- 2.2 事件外发 Outbox 事务表（保障 MySQL 与多实例 Redis Pub/Sub 的最终一致性）
CREATE TABLE IF NOT EXISTS `t_agent_event_outbox` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `event_id` varchar(64) NOT NULL COMMENT '关联 t_agent_session_event.event_id',
    `session_no` varchar(64) NOT NULL COMMENT '会话编号',
    `event_seq` bigint unsigned NOT NULL COMMENT '事件序号',
    `event_type` varchar(64) NOT NULL COMMENT '事件类型',
    `state` varchar(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING, PROCESSING, SUCCEEDED, DEAD',
    `attempt_count` int NOT NULL DEFAULT 0,
    `next_retry_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `lease_owner` varchar(64) DEFAULT NULL,
    `lease_until` datetime(3) DEFAULT NULL,
    `lease_generation` bigint unsigned NOT NULL DEFAULT 0,
    `last_error_code` varchar(64) DEFAULT NULL,
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `finished_time` datetime(3) DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_event_id` (`event_id`),
    KEY `idx_state_next_retry` (`state`, `next_retry_time`, `id`),
    KEY `idx_state_lease` (`state`, `lease_until`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='事件外发 Outbox 队列表';

-- 2.3 命令操作幂等表（REST/WS 共用，支持 actor 范围幂等防重）
CREATE TABLE IF NOT EXISTS `t_agent_command_operation` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `operation_no` varchar(64) NOT NULL COMMENT '操作回执编号 (OP_xxx)',
    `actor_type` varchar(16) NOT NULL COMMENT 'USER, STAFF, SYSTEM',
    `actor_id` varchar(64) NOT NULL COMMENT '操作主体唯一标识',
    `scope_key` varchar(128) NOT NULL COMMENT '业务作用域，如 SESSION:AS001, WORKFLOW:WF001',
    `client_operation_id` varchar(64) NOT NULL COMMENT '客户端提供的业务幂等键',
    `request_hash` char(64) NOT NULL COMMENT '标准化业务正文 SHA-256',
    `state` varchar(16) NOT NULL DEFAULT 'PROCESSING' COMMENT 'PROCESSING, SUCCEEDED, FAILED',
    `receipt_json` json DEFAULT NULL COMMENT '执行成功返回的快照结果',
    `result_ref` varchar(64) DEFAULT NULL COMMENT '产生的业务结果单号 (messageNo, handoffNo 等)',
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `expiry_time` datetime(3) NOT NULL COMMENT '幂等记录保留截止时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_actor_scope_op` (`actor_type`, `actor_id`, `scope_key`, `client_operation_id`),
    KEY `idx_expiry` (`expiry_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='命令操作执行幂等表';

-- 2.4 已读与接收游标表
CREATE TABLE IF NOT EXISTS `t_agent_read_cursor` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `session_no` varchar(64) NOT NULL,
    `actor_type` varchar(16) NOT NULL COMMENT 'USER, STAFF',
    `actor_id` varchar(64) NOT NULL,
    `last_delivered_event_seq` bigint unsigned NOT NULL DEFAULT 0 COMMENT '客户端已 ACK 的最大事件序号',
    `last_read_message_seq` bigint unsigned NOT NULL DEFAULT 0 COMMENT '已读的最大消息序号',
    `updated_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_session_actor` (`session_no`, `actor_type`, `actor_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会话已读与已送达游标表';

-- 2.5 用户收件箱版本指针表 (用于多端未读红点与列表同步加速)
CREATE TABLE IF NOT EXISTS `t_agent_inbox_head` (
    `user_id` bigint unsigned NOT NULL COMMENT '用户 ID',
    `revision` bigint unsigned NOT NULL DEFAULT 1 COMMENT '收件箱状态代际，有新会话或未读变更递增',
    `updated_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户会话收件箱版本表';

-- 2.6 业务对象与会话强绑定表（防止并发针对同一订单创建重复会话）
CREATE TABLE IF NOT EXISTS `t_agent_business_session_binding` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `user_id` bigint unsigned NOT NULL,
    `biz_type` varchar(32) NOT NULL COMMENT 'ORDER, DISPUTE 等',
    `biz_no` varchar(64) NOT NULL COMMENT '业务单号',
    `session_no` varchar(64) NOT NULL COMMENT '活跃会话号',
    `version` bigint unsigned NOT NULL DEFAULT 1,
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_biz` (`user_id`, `biz_type`, `biz_no`),
    KEY `idx_session` (`session_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='业务单号与客服会话绑定表';


-- =====================================================================================
-- 3. 人工坐席、技能组与路由配置表 (Section 10.3)
-- =====================================================================================

-- 3.1 客服坐席档案与容量表
CREATE TABLE IF NOT EXISTS `t_agent_staff` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `staff_id` varchar(64) NOT NULL COMMENT '坐席工号 (ST_xxx)',
    `user_id` bigint unsigned NOT NULL COMMENT '关联的用户系统帐号 ID',
    `display_name` varchar(64) NOT NULL COMMENT '对外公开展示名 (如 “客服小悦”)',
    `staff_role` varchar(16) NOT NULL DEFAULT 'AGENT' COMMENT 'AGENT, SUPERVISOR',
    `enabled` tinyint NOT NULL DEFAULT 1 COMMENT '是否启用',
    `availability` varchar(16) NOT NULL DEFAULT 'OFFLINE' COMMENT 'AVAILABLE, BUSY, AWAY, OFFLINE',
    `max_capacity` int NOT NULL DEFAULT 3 COMMENT '最大并发接待会话数',
    `used_capacity` int NOT NULL DEFAULT 0 COMMENT '当前占用接待容量',
    `version` bigint unsigned NOT NULL DEFAULT 1 COMMENT '并发容量乐观锁版本',
    `auth_version` bigint unsigned NOT NULL DEFAULT 1 COMMENT '权限代际，撤权时递增立即关闭连接',
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_staff_id` (`staff_id`),
    UNIQUE KEY `uk_user_id` (`user_id`),
    KEY `idx_avail_capacity` (`enabled`, `availability`, `used_capacity`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客服坐席档案与容量表';

-- 3.2 客服技能组定义表
CREATE TABLE IF NOT EXISTS `t_agent_skill_group` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `group_id` bigint unsigned NOT NULL,
    `code` varchar(32) NOT NULL COMMENT 'SENIOR_SUPPORT, SALES, AFTER_SALE, GENERAL_SUPPORT',
    `name` varchar(64) NOT NULL,
    `enabled` tinyint NOT NULL DEFAULT 1,
    `risk_ceiling` varchar(16) NOT NULL DEFAULT 'LOW' COMMENT '允许承接的最大风险等级 (LOW, NORMAL, HIGH, URGENT)',
    `hours_json` json DEFAULT NULL COMMENT '工作时间配置',
    `timezone` varchar(32) NOT NULL DEFAULT 'Asia/Shanghai',
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_group_id` (`group_id`),
    UNIQUE KEY `uk_code` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客服技能组定义表';

-- 3.3 坐席与技能组成员关联表
CREATE TABLE IF NOT EXISTS `t_agent_staff_skill` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `staff_id` varchar(64) NOT NULL,
    `group_id` bigint unsigned NOT NULL,
    `capabilities_json` json DEFAULT NULL COMMENT '具备的专项能力标签列表',
    `enabled` tinyint NOT NULL DEFAULT 1,
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_staff_group` (`staff_id`, `group_id`),
    KEY `idx_group_enabled` (`group_id`, `enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='坐席技能组成员关系表';

-- 3.4 版本化技能路由规则表
CREATE TABLE IF NOT EXISTS `t_agent_routing_rule` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `rule_code` varchar(64) NOT NULL COMMENT '规则编码',
    `version` int NOT NULL DEFAULT 1 COMMENT '版本号',
    `priority` int NOT NULL DEFAULT 100 COMMENT '执行匹配优先级，越小越优先',
    `condition_json` json NOT NULL COMMENT '意图、风险、业务类型匹配条件',
    `target_group` varchar(32) NOT NULL COMMENT '目标技能组 code',
    `fallback_groups_json` json DEFAULT NULL COMMENT '降级候选组列表',
    `state` varchar(16) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT, PUBLISHED, DISABLED',
    `published_by` bigint unsigned DEFAULT NULL,
    `published_time` datetime(3) DEFAULT NULL,
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_code_version` (`rule_code`, `version`),
    KEY `idx_state_priority` (`state`, `priority`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='版本化技能路由规则表';

-- 3.5 人工转接操作审计流水表
CREATE TABLE IF NOT EXISTS `t_agent_handoff_audit` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `action_no` varchar(64) NOT NULL COMMENT '流水编号 (ACT_xxx)',
    `handoff_no` varchar(64) NOT NULL COMMENT '关联转接单号',
    `from_staff_id` varchar(64) DEFAULT NULL,
    `to_staff_id` varchar(64) DEFAULT NULL,
    `from_group_id` bigint unsigned DEFAULT NULL,
    `to_group_id` bigint unsigned DEFAULT NULL,
    `old_epoch` bigint unsigned NOT NULL,
    `new_epoch` bigint unsigned NOT NULL,
    `action` varchar(32) NOT NULL COMMENT 'CLAIM, TRANSFER, RESOLVE, EXPIRE, CANCEL, ESCALATE',
    `reason` varchar(255) DEFAULT NULL,
    `operator_id` varchar(64) NOT NULL COMMENT '操作人 (staffId 或 userId)',
    `operation_id` varchar(64) DEFAULT NULL,
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_action_no` (`action_no`),
    KEY `idx_handoff_time` (`handoff_no`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='转人工分配与操作审计表';

-- 3.6 内部坐席私有沟通备注表（仅工作台可见，永不投影到客户会话）
CREATE TABLE IF NOT EXISTS `t_agent_staff_note` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `note_no` varchar(64) NOT NULL,
    `session_no` varchar(64) NOT NULL,
    `author_staff_id` varchar(64) NOT NULL,
    `redacted_content` text NOT NULL COMMENT '内部流转备注内容（已脱敏）',
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_note_no` (`note_no`),
    KEY `idx_session_time` (`session_no`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内部坐席会话备注表';

-- 3.7 Agent Copilot 辅助任务成果表
CREATE TABLE IF NOT EXISTS `t_agent_copilot_task` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `task_no` varchar(64) NOT NULL COMMENT '任务号 (CP_xxx)',
    `session_no` varchar(64) NOT NULL,
    `requester_staff_id` varchar(64) NOT NULL,
    `task_type` varchar(32) NOT NULL COMMENT 'REPLY_SUGGESTION, SESSION_SUMMARY',
    `based_on_event_seq` bigint unsigned NOT NULL COMMENT '触发时会话最新事件基准序号',
    `control_epoch` bigint unsigned NOT NULL COMMENT '任务创建时的控制权代际',
    `state` varchar(16) NOT NULL DEFAULT 'CREATED' COMMENT 'CREATED, PROCESSING, SUCCEEDED, FAILED, TIMEOUT',
    `run_no` varchar(64) DEFAULT NULL COMMENT '底层 AI Run 编号',
    `input_hash` char(64) NOT NULL COMMENT '输入数据哈希',
    `artifact_json` json DEFAULT NULL COMMENT '模型生成的建议草稿、摘要、工具引用结构',
    `expires_time` datetime(3) NOT NULL COMMENT '建议时效截止时间',
    `attempt_count` int NOT NULL DEFAULT 0,
    `next_retry_time` datetime(3) DEFAULT NULL,
    `lease_owner` varchar(64) DEFAULT NULL,
    `lease_until` datetime(3) DEFAULT NULL,
    `lease_generation` bigint unsigned NOT NULL DEFAULT 0,
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `finished_time` datetime(3) DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_task_no` (`task_no`),
    KEY `idx_state_retry` (`state`, `next_retry_time`, `id`),
    KEY `idx_session_created` (`session_no`, `created_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='坐席 Copilot 任务与建议表';


-- =====================================================================================
-- 4. 外部事件与主动触达表 (Section 10.4)
-- =====================================================================================

-- 4.1 外部事件登记来源表
CREATE TABLE IF NOT EXISTS `t_agent_event_source` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `source_code` varchar(32) NOT NULL COMMENT '来源编码 ORDER_SYSTEM, LOGISTICS_GATEWAY 等',
    `allowed_event_types` json NOT NULL COMMENT '允许上报的事件类型白名单',
    `key_ids` json NOT NULL COMMENT '已配置的公钥或 HMAC Key 编号列表',
    `secret_ref` varchar(128) NOT NULL COMMENT '密钥存储引用标识',
    `enabled` tinyint NOT NULL DEFAULT 1,
    `rate_limit` int NOT NULL DEFAULT 50 COMMENT '每秒最大接入限流 QPS',
    `version` bigint unsigned NOT NULL DEFAULT 1,
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_source_code` (`source_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='外部事件集成来源登记表';

-- 4.2 Webhook 防重放 Nonce 记录表
CREATE TABLE IF NOT EXISTS `t_agent_event_nonce` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `source_code` varchar(32) NOT NULL,
    `nonce_hash` char(64) NOT NULL,
    `expires_time` datetime(3) NOT NULL,
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_source_nonce` (`source_code`, `nonce_hash`),
    KEY `idx_expires` (`expires_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='外部 Webhook 请求 Nonce 防重放表';

-- 4.3 外部事件接入收件箱表 (Inbox)
CREATE TABLE IF NOT EXISTS `t_agent_event_inbox` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `receipt_no` varchar(64) NOT NULL COMMENT '回执号 (EIN_xxx)',
    `source` varchar(32) NOT NULL COMMENT '来源 source_code',
    `event_id` varchar(64) NOT NULL COMMENT '来源方事件唯一 ID',
    `event_type` varchar(64) NOT NULL COMMENT 'LOGISTICS_DELAYED, REFUND_SUCCEEDED 等',
    `aggregate_type` varchar(32) NOT NULL COMMENT '业务聚合类型 ORDER, REFUND',
    `aggregate_id` varchar(64) NOT NULL COMMENT '业务聚合主键 (orderNo, refundNo)',
    `aggregate_version` bigint unsigned NOT NULL DEFAULT 1 COMMENT '来源聚合版本',
    `payload_hash` char(64) NOT NULL COMMENT '载荷哈希',
    `verified_payload_json` json NOT NULL COMMENT '通过验签的原始业务载荷',
    `state` varchar(16) NOT NULL DEFAULT 'RECEIVED' COMMENT 'RECEIVED, PROCESSING, SUCCEEDED, SKIPPED, RETRY_WAIT, DEAD',
    `decision_code` varchar(32) DEFAULT NULL COMMENT '决策结论 SENT, MERGED, SKIPPED_OUTDATED 等',
    `lease_owner` varchar(64) DEFAULT NULL,
    `lease_until` datetime(3) DEFAULT NULL,
    `lease_generation` bigint unsigned NOT NULL DEFAULT 0,
    `attempt_count` int NOT NULL DEFAULT 0,
    `next_retry_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `trace_id` varchar(64) DEFAULT NULL,
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `finished_time` datetime(3) DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_source_event` (`source`, `event_id`),
    KEY `idx_state_retry` (`state`, `next_retry_time`, `id`),
    KEY `idx_aggregate_time` (`aggregate_type`, `aggregate_id`, `created_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='外部业务事件接入收件箱 (Inbox)';

-- 4.4 业务聚合事实版本游标表 (用于乱序纠正与最新状态追踪)
CREATE TABLE IF NOT EXISTS `t_agent_event_cursor` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `source` varchar(32) NOT NULL,
    `aggregate_type` varchar(32) NOT NULL,
    `aggregate_id` varchar(64) NOT NULL,
    `last_applied_version` bigint unsigned NOT NULL DEFAULT 0 COMMENT '已处理的最新聚合版本号',
    `fact_state_json` json DEFAULT NULL COMMENT '最新事实状态缓存快照',
    `version` bigint unsigned NOT NULL DEFAULT 1,
    `updated_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_source_aggregate` (`source`, `aggregate_type`, `aggregate_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='业务事件聚合水位游标表';

-- 4.5 主动触达策略规则表
CREATE TABLE IF NOT EXISTS `t_agent_outbound_rule` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `rule_code` varchar(64) NOT NULL,
    `version` int NOT NULL DEFAULT 1,
    `event_type` varchar(64) NOT NULL,
    `conditions_json` json NOT NULL COMMENT '命中规则匹配条件',
    `template_code` varchar(64) NOT NULL COMMENT '消息模板编码',
    `template_version` int NOT NULL DEFAULT 1,
    `delivery_policy_json` json NOT NULL COMMENT '发送渠道与模式策略 (BOT直接发/HUMAN草稿)',
    `frequency_policy_json` json NOT NULL COMMENT '频控与静默时段策略',
    `state` varchar(16) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT, PUBLISHED, DISABLED',
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_rule_version` (`rule_code`, `version`),
    KEY `idx_event_type_state` (`event_type`, `state`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='主动触达决策规则表';

-- 4.6 主动触达投递记录表
CREATE TABLE IF NOT EXISTS `t_agent_outbound_delivery` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `delivery_no` varchar(64) NOT NULL COMMENT '投递单号 (OD_xxx)',
    `inbox_id` bigint unsigned NOT NULL COMMENT '关联 t_agent_event_inbox.id',
    `recipient_user_id` bigint unsigned NOT NULL COMMENT '目标接收用户 ID',
    `business_dedupe_key` varchar(128) NOT NULL COMMENT '业务级去重键 (如 REFUND_SUCCEEDED:RF1001:1001)',
    `session_no` varchar(64) DEFAULT NULL COMMENT '写入的目标会话号',
    `message_no` varchar(64) DEFAULT NULL COMMENT '产生的消息编号',
    `decision` varchar(32) NOT NULL COMMENT 'SENT, SUPPRESSED, DEFERRED, STAFF_REVIEW, SKIPPED',
    `delivery_state` varchar(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING, PROCESSING, SUCCEEDED, FAILED',
    `rule_code` varchar(64) NOT NULL,
    `rule_version` int NOT NULL,
    `template_code` varchar(64) NOT NULL,
    `template_version` int NOT NULL,
    `defer_until` datetime(3) DEFAULT NULL COMMENT '延迟发送等待时间（如静默时段顺延）',
    `lease_owner` varchar(64) DEFAULT NULL,
    `lease_until` datetime(3) DEFAULT NULL,
    `lease_generation` bigint unsigned NOT NULL DEFAULT 0,
    `attempt_count` int NOT NULL DEFAULT 0,
    `next_retry_time` datetime(3) DEFAULT NULL,
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `finished_time` datetime(3) DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_recipient_dedupe` (`recipient_user_id`, `business_dedupe_key`),
    KEY `idx_decision_defer` (`decision`, `defer_until`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='主动触达任务投递表';

-- 4.7 用户主动触达个性化配置表
CREATE TABLE IF NOT EXISTS `t_agent_outbound_setting` (
    `user_id` bigint unsigned NOT NULL,
    `service_notice_enabled` tinyint NOT NULL DEFAULT 1 COMMENT '是否接收交易服务变更通知',
    `proactive_assistance_enabled` tinyint NOT NULL DEFAULT 1 COMMENT '是否接收主动服务询问与建议',
    `quiet_hours_json` json DEFAULT NULL COMMENT '静默免打扰时间区间 [22, 8]',
    `timezone` varchar(32) NOT NULL DEFAULT 'Asia/Shanghai',
    `version` bigint unsigned NOT NULL DEFAULT 1,
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户主动通知设置表';

-- 4.8 主动触达频控配额原子计数表
CREATE TABLE IF NOT EXISTS `t_agent_outbound_quota` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `user_id` bigint unsigned NOT NULL,
    `rule_scope` varchar(64) NOT NULL COMMENT '频控维度 (PROACTIVE_DAILY, BIZ_COOLDOWN 等)',
    `window_start` datetime(3) NOT NULL COMMENT '时间窗口起点',
    `reserved_count` int NOT NULL DEFAULT 0 COMMENT '预占计数',
    `sent_count` int NOT NULL DEFAULT 0 COMMENT '实际确认发送计数',
    `version` bigint unsigned NOT NULL DEFAULT 1,
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_scope_window` (`user_id`, `rule_scope`, `window_start`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='主动触达配额与频控表';


-- =====================================================================================
-- 5. SOP 任务流与结构化卡片表 (Section 10.5)
-- =====================================================================================

-- 5.1 版本化 SOP 流程定义表
CREATE TABLE IF NOT EXISTS `t_agent_sop_definition` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `code` varchar(64) NOT NULL COMMENT '流程定义编码 AFTER_SALE_REVIEW 等',
    `version` int NOT NULL DEFAULT 1 COMMENT '流程定义版本',
    `definition_json` json NOT NULL COMMENT '不可变的状态机节点与边图定义',
    `checksum` char(64) NOT NULL COMMENT '定义 JSON SHA-256 校验和',
    `state` varchar(16) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT, PUBLISHED, DISABLED',
    `published_by` bigint unsigned DEFAULT NULL,
    `published_time` datetime(3) DEFAULT NULL,
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_code_version` (`code`, `version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='版本化 SOP 工作流定义表';

-- 5.2 动态表单元数据定义表
CREATE TABLE IF NOT EXISTS `t_agent_form_definition` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `code` varchar(64) NOT NULL COMMENT '表单编码 after_sale_reason 等',
    `version` int NOT NULL DEFAULT 1,
    `json_schema` json NOT NULL COMMENT 'JSON Schema Draft 2020-12 数据校验规范',
    `ui_schema` json DEFAULT NULL COMMENT '前端渲染控件与提示规范',
    `sensitive_fields_json` json DEFAULT NULL COMMENT '私密敏感字段列表（做单独脱敏与权限控制）',
    `state` varchar(16) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT, PUBLISHED, DISABLED',
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_code_version` (`code`, `version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='动态表单元数据规范定义表';

-- 5.3 SOP 流程运行实例表
CREATE TABLE IF NOT EXISTS `t_agent_sop_instance` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `workflow_no` varchar(64) NOT NULL COMMENT '工作流实例号 (WF_xxx)',
    `definition_code` varchar(64) NOT NULL,
    `definition_version` int NOT NULL,
    `user_id` bigint unsigned NOT NULL,
    `biz_type` varchar(32) DEFAULT NULL,
    `biz_no` varchar(64) DEFAULT NULL,
    `origin_session_no` varchar(64) NOT NULL COMMENT '发起初始会话号',
    `current_session_no` varchar(64) NOT NULL COMMENT '当前绑定的活跃会话号',
    `state` varchar(16) NOT NULL DEFAULT 'RUNNING' COMMENT 'RUNNING, WAITING_USER, WAITING_EXTERNAL, WAITING_HUMAN, PAUSED, CANCELED, EXPIRED, FAILED, COMPLETED',
    `current_node` varchar(64) NOT NULL COMMENT '当前停靠状态机节点 ID',
    `variables_json` json DEFAULT NULL COMMENT '流程持久化上下文变量',
    `version` bigint unsigned NOT NULL DEFAULT 1 COMMENT '乐观锁版本',
    `due_time` datetime(3) DEFAULT NULL COMMENT '流程整体或当前节点超时截止时间',
    `result_ref` varchar(64) DEFAULT NULL COMMENT '终态业务结果单号 (如 disputeNo)',
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_workflow_no` (`workflow_no`),
    KEY `idx_user_state_time` (`user_id`, `state`, `updated_time`),
    KEY `idx_state_due` (`state`, `due_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SOP 流程运行实例表';

-- 5.4 业务对象与活跃 SOP 实例绑定表（保证同订单同流程同时只有一个活跃实例）
CREATE TABLE IF NOT EXISTS `t_agent_sop_binding` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `user_id` bigint unsigned NOT NULL,
    `biz_type` varchar(32) NOT NULL,
    `biz_no` varchar(64) NOT NULL,
    `workflow_code` varchar(64) NOT NULL,
    `active_workflow_no` varchar(64) NOT NULL,
    `version` bigint unsigned NOT NULL DEFAULT 1,
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_biz_wf` (`user_id`, `biz_type`, `biz_no`, `workflow_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='业务单号与活跃 SOP 实例绑定表';

-- 5.5 SOP 交互任务明细表 (表单填写、人工审核、用户确认)
CREATE TABLE IF NOT EXISTS `t_agent_sop_task` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `task_no` varchar(64) NOT NULL COMMENT '任务号 (WT_xxx)',
    `workflow_no` varchar(64) NOT NULL,
    `node_execution_no` varchar(64) NOT NULL COMMENT '节点单次执行流水编号',
    `node_id` varchar(64) NOT NULL COMMENT '节点定义 ID',
    `kind` varchar(32) NOT NULL COMMENT 'FORM, HUMAN_TASK, USER_CONFIRM',
    `state` varchar(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING, PROCESSING, COMPLETED, CANCELED, EXPIRED',
    `version` bigint unsigned NOT NULL DEFAULT 1,
    `assigned_group` varchar(32) DEFAULT NULL COMMENT '指派的人工技能组',
    `assigned_staff_id` varchar(64) DEFAULT NULL COMMENT '认领审核的坐席 ID',
    `form_instance_id` varchar(64) DEFAULT NULL COMMENT '表单实例唯一标识',
    `draft_json` json DEFAULT NULL COMMENT '草稿保存字段（允许缺必填）',
    `submitted_json` json DEFAULT NULL COMMENT '已提交经校验完整字段',
    `due_time` datetime(3) DEFAULT NULL,
    `reminder_count` int NOT NULL DEFAULT 0,
    `lease_owner` varchar(64) DEFAULT NULL,
    `lease_until` datetime(3) DEFAULT NULL,
    `lease_generation` bigint unsigned NOT NULL DEFAULT 0,
    `attempt_count` int NOT NULL DEFAULT 0,
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_task_no` (`task_no`),
    UNIQUE KEY `uk_node_execution` (`node_execution_no`),
    KEY `idx_state_due` (`state`, `due_time`, `id`),
    KEY `idx_group_state` (`assigned_group`, `state`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SOP 人机交互与审核任务表';

-- 5.6 SOP 节点迁移流转审计表
CREATE TABLE IF NOT EXISTS `t_agent_sop_transition` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `transition_no` varchar(64) NOT NULL COMMENT '流转流水号 (TRN_xxx)',
    `workflow_no` varchar(64) NOT NULL,
    `operation_id` varchar(64) NOT NULL COMMENT '推进操作幂等 ID',
    `from_node` varchar(64) NOT NULL,
    `to_node` varchar(64) NOT NULL,
    `from_state` varchar(16) NOT NULL,
    `to_state` varchar(16) NOT NULL,
    `variables_hash` char(64) NOT NULL COMMENT '流转后变量集快照哈希',
    `operator` varchar(64) NOT NULL COMMENT '操作人 (USER, STAFF_101, SYSTEM)',
    `reason` varchar(255) DEFAULT NULL,
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_workflow_op` (`workflow_no`, `operation_id`),
    KEY `idx_workflow_time` (`workflow_no`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SOP 节点流转审计流水表';

-- 5.7 SOP 外部业务命令执行表 (SERVICE_COMMAND)
CREATE TABLE IF NOT EXISTS `t_agent_sop_command` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `command_no` varchar(64) NOT NULL COMMENT '命令编号 (CMD_xxx)',
    `workflow_no` varchar(64) NOT NULL,
    `node_execution_no` varchar(64) NOT NULL,
    `operation_id` varchar(64) NOT NULL COMMENT '向下游业务系统传递的严格幂等键',
    `command_type` varchar(64) NOT NULL COMMENT 'CREATE_AFTER_SALE, SUBMIT_EVIDENCE, SUBMIT_DECISION',
    `request_hash` char(64) NOT NULL,
    `authorized_input_json` json NOT NULL COMMENT '经过鉴权和校验的业务入参',
    `state` varchar(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING, PROCESSING, SUCCEEDED, FAILED, UNKNOWN',
    `result_json` json DEFAULT NULL COMMENT '下游业务返回结果',
    `business_ref` varchar(64) DEFAULT NULL COMMENT '产生的下游实体编号 (如 disputeNo)',
    `lease_owner` varchar(64) DEFAULT NULL,
    `lease_until` datetime(3) DEFAULT NULL,
    `lease_generation` bigint unsigned NOT NULL DEFAULT 0,
    `attempt_count` int NOT NULL DEFAULT 0,
    `next_retry_time` datetime(3) DEFAULT NULL,
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `finished_time` datetime(3) DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_operation_id` (`operation_id`),
    KEY `idx_state_retry` (`state`, `next_retry_time`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='SOP 外部幂等业务命令表';

-- 5.8 SOP 外部事件等待契约表 (WAIT_EVENT)
CREATE TABLE IF NOT EXISTS `t_agent_sop_event_wait` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `wait_no` varchar(64) NOT NULL COMMENT '等待注册编号 (EW_xxx)',
    `workflow_no` varchar(64) NOT NULL,
    `node_execution_no` varchar(64) NOT NULL,
    `source` varchar(32) NOT NULL COMMENT '预期事件源',
    `event_type` varchar(64) NOT NULL COMMENT '预期事件类型',
    `correlation_key` varchar(128) NOT NULL COMMENT '关联业务键 (如 dispute:RF1001)',
    `expected_biz_version` bigint unsigned DEFAULT NULL,
    `state` varchar(16) NOT NULL DEFAULT 'WAITING' COMMENT 'WAITING, MATCHED, TIMEOUT, CANCELED',
    `matched_event_id` varchar(64) DEFAULT NULL COMMENT '命中并消费的事件 ID',
    `due_time` datetime(3) DEFAULT NULL COMMENT '最大等待截止时间',
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_workflow_node` (`workflow_no`, `node_execution_no`),
    KEY `idx_corr_event_state` (`correlation_key`, `event_type`, `state`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SOP 外部事件回执等待表';

-- 5.9 消息富媒体卡片实例表
CREATE TABLE IF NOT EXISTS `t_agent_card_instance` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `card_id` varchar(64) NOT NULL COMMENT '卡片全局唯一 ID (CARD_xxx)',
    `message_no` varchar(64) NOT NULL COMMENT '关联所属消息编号',
    `session_no` varchar(64) NOT NULL,
    `card_type` varchar(32) NOT NULL COMMENT 'order_timeline, product_showcase, dynamic_form, rating 等',
    `card_version` int NOT NULL DEFAULT 1,
    `revision` int NOT NULL DEFAULT 1 COMMENT '卡片交互修订版本',
    `state` varchar(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE, EXPIRED, SUBMITTED, REVOKED',
    `expires_time` datetime(3) DEFAULT NULL,
    `public_data_json` json NOT NULL COMMENT '脱敏公开展示数据',
    `action_binding_json` json DEFAULT NULL COMMENT '绑定的服务端受控动作白名单',
    `workflow_ref` varchar(64) DEFAULT NULL COMMENT '关联的 SOP 流程号',
    `task_ref` varchar(64) DEFAULT NULL COMMENT '关联的任务号',
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_card_id` (`card_id`),
    KEY `idx_message_id` (`message_no`, `id`),
    KEY `idx_session_time` (`session_no`, `created_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='消息富媒体卡片实例表';


-- =====================================================================================
-- 6. 历史数据初始回填与系统默认初始数据 (Section 14.1)
-- =====================================================================================

-- 6.1 初始化系统默认客服技能组 (若不存在)
INSERT IGNORE INTO `t_agent_skill_group` (`group_id`, `code`, `name`, `risk_ceiling`, `enabled`, `timezone`) VALUES
(1, 'SENIOR_SUPPORT', '资深高级客服组', 'URGENT', 1, 'Asia/Shanghai'),
(2, 'AFTER_SALE',     '售后保障服务组', 'NORMAL', 1, 'Asia/Shanghai'),
(3, 'SALES',          '商品导购销售组', 'NORMAL', 1, 'Asia/Shanghai'),
(4, 'GENERAL_SUPPORT','通用综合客服组', 'NORMAL', 1, 'Asia/Shanghai');

-- 6.2 初始化第一版默认路由规则 (v1, PUBLISHED)
INSERT IGNORE INTO `t_agent_routing_rule` (`rule_code`, `version`, `priority`, `condition_json`, `target_group`, `fallback_groups_json`, `state`, `published_time`) VALUES
('RULE_URGENT_ESCALATE', 1, 10, '{"riskLevels":["URGENT"]}', 'SENIOR_SUPPORT', '["GENERAL_SUPPORT"]', 'PUBLISHED', now(3)),
('RULE_HIGH_RISK_CLAIM',  1, 20, '{"riskLevels":["HIGH"],"keywords":["被盗","资金损失","赔偿","仲裁"]}', 'SENIOR_SUPPORT', '["GENERAL_SUPPORT"]', 'PUBLISHED', now(3)),
('RULE_AFTER_SALE_ROUTE', 1, 30, '{"intents":["AFTER_SALE_PROGRESS","AFTER_SALE_RULE","RETURN_PROCESS"]}', 'AFTER_SALE', '["GENERAL_SUPPORT"]', 'PUBLISHED', now(3)),
('RULE_PRE_SALE_ROUTE',   1, 40, '{"intents":["ITEM_RECOMMENDATION","PRICE_INQUIRY","DISCOUNT"]}', 'SALES', '["GENERAL_SUPPORT"]', 'PUBLISHED', now(3)),
('RULE_DEFAULT_FALLBACK', 1, 99, '{"default":true}', 'GENERAL_SUPPORT', '[]', 'PUBLISHED', now(3));

-- 6.3 历史数据状态平滑映射（兼容旧状态至新权威字段）
-- 会话状态迁移：若已有 status='HANDOFF' 且无对应 service_mode，设置为 QUEUED
UPDATE `t_agent_session` SET `service_mode` = 'QUEUED' WHERE `status` = 'HANDOFF' AND `service_mode` = 'BOT';
UPDATE `t_agent_session` SET `service_mode` = 'NONE'   WHERE `status` IN ('CLOSED', 'EXPIRED') AND `service_mode` = 'BOT';

-- 历史消息 sender_type 回填
UPDATE `t_agent_message` SET `sender_type` = 'AI'     WHERE `role` = 'AGENT' AND `sender_type` = 'USER';
UPDATE `t_agent_message` SET `sender_type` = 'SYSTEM' WHERE `role` = 'SYSTEM_SUMMARY' AND `sender_type` = 'USER';
UPDATE `t_agent_message` SET `sender_type` = 'USER'   WHERE `role` = 'USER' AND `sender_type` = 'USER';
