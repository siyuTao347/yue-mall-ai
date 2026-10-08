-- 第一批阶段一：担保交易一致性增量迁移
-- 执行前确认 MySQL 用户具备 db_user、db_order、db_item 的 DDL 权限。

CREATE DATABASE IF NOT EXISTS db_user DEFAULT CHARACTER SET utf8mb4;
USE db_user;

CREATE TABLE IF NOT EXISTS `t_merchant_credit_operation` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `operation_key` varchar(128) NOT NULL,
    `merchant_id` bigint unsigned NOT NULL,
    `operation_type` varchar(32) NOT NULL,
    `score` decimal(5,2) DEFAULT NULL,
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_operation_key` (`operation_key`),
    KEY `idx_merchant_id` (`merchant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商家信用操作幂等表';

CREATE DATABASE IF NOT EXISTS db_order DEFAULT CHARACTER SET utf8mb4;
USE db_order;

CREATE TABLE IF NOT EXISTS `t_trade_orchestration_task` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `task_no` varchar(64) NOT NULL,
    `task_type` varchar(64) NOT NULL,
    `biz_type` varchar(32) NOT NULL,
    `biz_no` varchar(64) NOT NULL,
    `status` varchar(24) NOT NULL DEFAULT 'INIT',
    `context_json` json NOT NULL,
    `idempotency_key` varchar(128) NOT NULL,
    `retry_count` int NOT NULL DEFAULT 0,
    `max_retry_count` int NOT NULL DEFAULT 5,
    `next_execute_time` datetime(3) DEFAULT NULL,
    `locked_by` varchar(64) DEFAULT NULL,
    `locked_until` datetime(3) DEFAULT NULL,
    `trace_id` varchar(64) DEFAULT NULL,
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_task_no` (`task_no`),
    UNIQUE KEY `uk_task_idempotency` (`idempotency_key`),
    KEY `idx_status_next_time` (`status`, `next_execute_time`),
    KEY `idx_biz` (`biz_type`, `biz_no`),
    KEY `idx_locked_until` (`locked_until`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='担保交易编排任务表';

CREATE TABLE IF NOT EXISTS `t_trade_orchestration_step` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `task_no` varchar(64) NOT NULL,
    `step_no` int NOT NULL,
    `step_name` varchar(64) NOT NULL,
    `step_type` varchar(32) NOT NULL,
    `status` varchar(24) NOT NULL DEFAULT 'INIT',
    `idempotency_key` varchar(128) NOT NULL,
    `request_json` json DEFAULT NULL,
    `response_json` json DEFAULT NULL,
    `attempt_count` int NOT NULL DEFAULT 0,
    `max_attempt_count` int NOT NULL DEFAULT 5,
    `next_execute_time` datetime(3) DEFAULT NULL,
    `last_error` varchar(512) DEFAULT NULL,
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_task_step` (`task_no`, `step_no`),
    UNIQUE KEY `uk_step_idempotency` (`idempotency_key`),
    KEY `idx_task_status` (`task_no`, `status`),
    KEY `idx_status_next_time` (`status`, `next_execute_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='担保交易编排步骤表';

USE db_item;

ALTER TABLE `t_asset_reservation`
    ADD COLUMN `idempotency_key` varchar(128) DEFAULT NULL AFTER `order_no`,
    ADD COLUMN `snapshot_json` json DEFAULT NULL AFTER `idempotency_key`;

UPDATE `t_asset_reservation`
SET `idempotency_key` = CONCAT('ASSET_RESERVE:', `order_no`)
WHERE `idempotency_key` IS NULL OR `idempotency_key` = '';

ALTER TABLE `t_asset_reservation`
    MODIFY COLUMN `idempotency_key` varchar(128) NOT NULL,
    ADD UNIQUE KEY `uk_idempotency_key` (`idempotency_key`);

USE db_order;

ALTER TABLE `t_trade_order`
    MODIFY COLUMN `order_status` varchar(24) NOT NULL DEFAULT 'CREATE_PENDING';

-- 阶段一一致性对账需要只读访问 db_item.t_asset_reservation 与
-- db_user.t_fund_transaction。生产环境请由 DBA 按最小权限原则单独授权。
