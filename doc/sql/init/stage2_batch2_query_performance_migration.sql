-- 第二批查询与性能优化增量迁移
-- 生产执行前请先确认索引不存在，并优先使用 MySQL 8 在线 DDL。

USE db_order;

ALTER TABLE `t_trade_order`
    ADD KEY `idx_buyer_created` (`buyer_id`, `created_time`, `id`),
    ADD KEY `idx_seller_created` (`seller_id`, `created_time`, `id`),
    ADD KEY `idx_status_created` (`order_status`, `created_time`, `id`);

USE db_user;

ALTER TABLE `t_fund_flow`
    ADD KEY `idx_owner_id` (`owner_type`, `owner_id`, `id`);

ALTER TABLE `t_merchant`
    ADD KEY `idx_status_updated` (`status`, `updated_time`, `id`),
    ADD KEY `idx_user_id` (`user_id`);

ALTER TABLE `t_withdraw_request`
    ADD KEY `idx_user_status_created` (`user_id`, `status`, `created_time`, `id`),
    ADD KEY `idx_status_created_id` (`status`, `created_time`, `id`);

ALTER TABLE db_item.`t_item`
    ADD COLUMN `updated_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    ADD KEY `idx_merchant_audit_updated` (`merchant_id`, `audit_status`, `updated_time`, `id`),
    ADD KEY `idx_audit_updated` (`audit_status`, `updated_time`, `id`);

USE db_risk;

ALTER TABLE `t_risk_case`
    ADD KEY `idx_status_level_updated` (`status`, `risk_level`, `updated_time`, `id`),
    ADD KEY `idx_command_status_updated` (`command_status`, `updated_time`, `id`),
    ADD KEY `idx_subject` (`subject_type`, `subject_id`, `updated_time`);

ALTER TABLE `t_risk_indicator`
    ADD KEY `idx_indicator_updated` (`subject_type`, `subject_id`, `window_type`, `updated_time`);

CREATE TABLE IF NOT EXISTS `t_risk_indicator_refresh_task` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `subject_type` varchar(16) NOT NULL,
    `subject_id` bigint unsigned NOT NULL,
    `metric_codes_json` json DEFAULT NULL,
    `status` varchar(16) NOT NULL DEFAULT 'PENDING',
    `retry_count` int NOT NULL DEFAULT 0,
    `max_retry_count` int NOT NULL DEFAULT 3,
    `next_execute_time` datetime(3) NOT NULL,
    `last_error` varchar(512) DEFAULT NULL,
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_subject_pending` (`subject_type`, `subject_id`, `status`),
    KEY `idx_status_next` (`status`, `next_execute_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='风控指标刷新任务表';
