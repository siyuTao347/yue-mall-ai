-- 虚拟资产商城阶段一数据库结构
-- 该脚本是增量迁移脚本，需要在已有基础库上执行一次。

CREATE DATABASE IF NOT EXISTS db_user DEFAULT CHARACTER SET utf8mb4;
USE db_user;

ALTER TABLE `t_user`
    ADD COLUMN `role` varchar(16) NOT NULL DEFAULT 'USER' COMMENT '用户角色: USER, ADMIN';

CREATE TABLE IF NOT EXISTS `t_merchant` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `user_id` bigint unsigned NOT NULL,
    `merchant_name` varchar(64) NOT NULL,
    `contact_email` varchar(128) NOT NULL,
    `introduction` varchar(512) DEFAULT NULL,
    `status` varchar(16) NOT NULL DEFAULT 'SUBMITTED',
    `level` tinyint NOT NULL DEFAULT 1,
    `reject_reason` varchar(255) DEFAULT NULL,
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_id` (`user_id`),
    UNIQUE KEY `uk_merchant_name` (`merchant_name`),
    KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='虚拟资产商家表';

CREATE TABLE IF NOT EXISTS `t_merchant_audit` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `merchant_id` bigint unsigned NOT NULL,
    `action` varchar(16) NOT NULL,
    `auditor_id` bigint unsigned DEFAULT NULL,
    `reason` varchar(255) DEFAULT NULL,
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    KEY `idx_merchant_id` (`merchant_id`),
    KEY `idx_created_time` (`created_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商家审核记录表';

CREATE TABLE IF NOT EXISTS `t_merchant_deposit` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `merchant_id` bigint unsigned NOT NULL,
    `total_amount` decimal(18,2) NOT NULL DEFAULT 0.00,
    `frozen_amount` decimal(18,2) NOT NULL DEFAULT 0.00,
    `deducted_amount` decimal(18,2) NOT NULL DEFAULT 0.00,
    `updated_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_merchant_id` (`merchant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商家保证金账户表';

CREATE TABLE IF NOT EXISTS `t_user_account` (
    `user_id` bigint unsigned NOT NULL,
    `available_amount` decimal(18,2) NOT NULL DEFAULT 0.00,
    `pending_settle_amount` decimal(18,2) NOT NULL DEFAULT 0.00,
    `frozen_amount` decimal(18,2) NOT NULL DEFAULT 0.00,
    `version` int NOT NULL DEFAULT 0,
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户内部资金账户表';

CREATE TABLE IF NOT EXISTS `t_platform_account` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `account_code` varchar(32) NOT NULL,
    `escrow_amount` decimal(18,2) NOT NULL DEFAULT 0.00,
    `revenue_amount` decimal(18,2) NOT NULL DEFAULT 0.00,
    `withdraw_pending_amount` decimal(18,2) NOT NULL DEFAULT 0.00,
    `deposit_amount` decimal(18,2) NOT NULL DEFAULT 0.00,
    `version` int NOT NULL DEFAULT 0,
    `updated_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_account_code` (`account_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='平台内部账户表';

CREATE TABLE IF NOT EXISTS `t_fund_transaction` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `transaction_no` varchar(64) NOT NULL,
    `business_type` varchar(32) NOT NULL COMMENT '业务类型: PAY_FREEZE, REFUND, SETTLE, SETTLE_AVAILABLE, WITHDRAW_FREEZE, WITHDRAW_PAYOUT, WITHDRAW_REJECT',
    `order_no` varchar(64) DEFAULT NULL,
    `withdraw_no` varchar(64) DEFAULT NULL,
    `merchant_id` bigint unsigned DEFAULT NULL,
    `user_id` bigint unsigned DEFAULT NULL,
    `amount` decimal(18,2) NOT NULL,
    `status` varchar(16) NOT NULL DEFAULT 'SUCCESS',
    `idempotency_key` varchar(128) NOT NULL,
    `remark` varchar(255) DEFAULT NULL,
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_transaction_no` (`transaction_no`),
    UNIQUE KEY `uk_idempotency_key` (`idempotency_key`),
    KEY `idx_order_no` (`order_no`),
    KEY `idx_withdraw_no` (`withdraw_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='资金交易头表';

CREATE TABLE IF NOT EXISTS `t_fund_flow` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `transaction_no` varchar(64) NOT NULL,
    `owner_type` varchar(16) NOT NULL,
    `owner_id` bigint unsigned DEFAULT NULL,
    `account_type` varchar(32) NOT NULL,
    `direction` varchar(8) NOT NULL,
    `amount` decimal(18,2) NOT NULL,
    `balance_after` decimal(18,2) DEFAULT NULL,
    `memo` varchar(255) DEFAULT NULL,
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tx_owner_account_direction` (`transaction_no`, `owner_type`, `owner_id`, `account_type`, `direction`),
    KEY `idx_owner` (`owner_type`, `owner_id`, `account_type`),
    KEY `idx_created_time` (`created_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='资金流水明细表';

CREATE TABLE IF NOT EXISTS `t_withdraw_request` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `withdraw_no` varchar(64) NOT NULL,
    `merchant_id` bigint unsigned NOT NULL,
    `user_id` bigint unsigned NOT NULL,
    `amount` decimal(18,2) NOT NULL,
    `mock_account` varchar(128) NOT NULL,
    `status` varchar(24) NOT NULL DEFAULT 'SUBMITTED',
    `audit_admin_id` bigint unsigned DEFAULT NULL,
    `audit_reason` varchar(255) DEFAULT NULL,
    `mock_payout_no` varchar(64) DEFAULT NULL,
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_withdraw_no` (`withdraw_no`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_status_time` (`status`, `created_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='卖家提现申请表';

CREATE TABLE IF NOT EXISTS `t_fund_reconciliation_diff` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `diff_key` varchar(128) NOT NULL,
    `diff_type` varchar(64) NOT NULL,
    `biz_no` varchar(64) NOT NULL,
    `expected_amount` decimal(18,2) NOT NULL DEFAULT 0.00,
    `actual_amount` decimal(18,2) NOT NULL DEFAULT 0.00,
    `status` varchar(16) NOT NULL DEFAULT 'OPEN',
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_diff_key` (`diff_key`),
    KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='资金对账差异表';

CREATE TABLE IF NOT EXISTS `t_fee_config` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `category_id` bigint unsigned NOT NULL,
    `merchant_level` tinyint NOT NULL DEFAULT 1,
    `fee_rate` decimal(6,4) NOT NULL,
    `min_fee` decimal(10,2) NOT NULL DEFAULT 0.01,
    `max_fee` decimal(10,2) NOT NULL DEFAULT 100.00,
    `enabled` tinyint NOT NULL DEFAULT 1,
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_category_level` (`category_id`, `merchant_level`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='交易手续费配置表';

CREATE TABLE IF NOT EXISTS `t_merchant_credit` (
    `merchant_id` bigint unsigned NOT NULL,
    `total_order_count` int NOT NULL DEFAULT 0,
    `completed_order_count` int NOT NULL DEFAULT 0,
    `refund_order_count` int NOT NULL DEFAULT 0,
    `dispute_order_count` int NOT NULL DEFAULT 0,
    `avg_score` decimal(3,2) NOT NULL DEFAULT 5.00,
    `credit_score` int NOT NULL DEFAULT 80,
    `updated_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`merchant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商家信用统计表';

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

CREATE TABLE IF NOT EXISTS db_item.`t_item_category` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `category_name` varchar(64) NOT NULL,
    `parent_id` bigint unsigned DEFAULT NULL,
    `asset_type` varchar(32) NOT NULL,
    `delivery_mode` varchar(32) NOT NULL,
    `required_deposit` decimal(18,2) NOT NULL DEFAULT 100.00,
    `sort_order` int NOT NULL DEFAULT 0,
    `enabled` tinyint NOT NULL DEFAULT 1,
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    KEY `idx_parent_id` (`parent_id`),
    KEY `idx_asset_type` (`asset_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='虚拟资产类目表';

ALTER TABLE db_item.`t_item`
    ADD COLUMN `merchant_id` bigint unsigned NOT NULL DEFAULT 0 COMMENT '商家ID',
    ADD COLUMN `seller_id` bigint unsigned NOT NULL DEFAULT 0 COMMENT '卖家用户ID',
    ADD COLUMN `asset_type` varchar(32) NOT NULL DEFAULT 'VIRTUAL_SKIN' COMMENT '资产类型',
    ADD COLUMN `delivery_mode` varchar(32) NOT NULL DEFAULT 'MANUAL_DELIVERY' COMMENT '交付方式',
    ADD COLUMN `source_description` varchar(512) DEFAULT NULL COMMENT '资产来源说明',
    ADD COLUMN `risk_notice` varchar(1024) NOT NULL DEFAULT '虚拟资产存在交易风险，请确认后购买' COMMENT '风险声明',
    ADD COLUMN `audit_status` varchar(16) NOT NULL DEFAULT 'APPROVED' COMMENT '审核状态',
    ADD COLUMN `audit_remark` varchar(255) DEFAULT NULL COMMENT '审核备注';

ALTER TABLE db_item.`t_item`
    ADD INDEX `idx_merchant_status` (`merchant_id`, `status`),
    ADD INDEX `idx_seller_id` (`seller_id`),
    ADD INDEX `idx_audit_status` (`audit_status`),
    ADD INDEX `idx_asset_type` (`asset_type`);

-- 旧数据补齐卖家用户ID，保证既有商品仍能进入担保交易链路。
UPDATE db_item.`t_item` i
JOIN db_user.`t_merchant` m ON i.merchant_id = m.id
SET i.seller_id = m.user_id
WHERE i.seller_id = 0;

CREATE TABLE IF NOT EXISTS db_item.`t_item_audit` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `item_id` bigint unsigned NOT NULL,
    `action` varchar(16) NOT NULL,
    `auditor_id` bigint unsigned DEFAULT NULL,
    `reason` varchar(255) DEFAULT NULL,
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    KEY `idx_item_id` (`item_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品审核记录表';

CREATE TABLE IF NOT EXISTS db_item.`t_card_secret` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `item_id` bigint unsigned NOT NULL,
    `merchant_id` bigint unsigned NOT NULL,
    `secret_cipher` varbinary(1024) NOT NULL,
    `secret_hash` char(64) NOT NULL,
    `secret_mask` varchar(64) NOT NULL,
    `status` varchar(16) NOT NULL DEFAULT 'AVAILABLE',
    `order_no` varchar(64) DEFAULT NULL,
    `reservation_no` varchar(64) DEFAULT NULL,
    `locked_time` datetime(3) DEFAULT NULL,
    `sold_time` datetime(3) DEFAULT NULL,
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_item_hash` (`item_id`, `secret_hash`),
    KEY `idx_item_status` (`item_id`, `status`),
    KEY `idx_order_no` (`order_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='卡密库存表';

CREATE TABLE IF NOT EXISTS db_item.`t_asset_reservation` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `reservation_no` varchar(64) NOT NULL,
    `order_no` varchar(64) NOT NULL,
    `idempotency_key` varchar(128) NOT NULL,
    `snapshot_json` json DEFAULT NULL,
    `item_id` bigint unsigned NOT NULL,
    `merchant_id` bigint unsigned NOT NULL,
    `asset_type` varchar(32) NOT NULL,
    `quantity` int NOT NULL DEFAULT 1,
    `card_secret_ids` json DEFAULT NULL,
    `status` varchar(16) NOT NULL DEFAULT 'RESERVED',
    `expire_time` datetime(3) NOT NULL,
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_reservation_no` (`reservation_no`),
    UNIQUE KEY `uk_order_no` (`order_no`),
    UNIQUE KEY `uk_idempotency_key` (`idempotency_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='交易资产预留表';

CREATE DATABASE IF NOT EXISTS db_order DEFAULT CHARACTER SET utf8mb4;
USE db_order;

CREATE TABLE IF NOT EXISTS `t_trade_order` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `order_no` varchar(64) NOT NULL,
    `buyer_id` bigint unsigned NOT NULL,
    `seller_id` bigint unsigned NOT NULL,
    `merchant_id` bigint unsigned NOT NULL,
    `item_id` bigint unsigned NOT NULL,
    `item_snapshot` json NOT NULL,
    `quantity` int NOT NULL DEFAULT 1,
    `order_amount` decimal(18,2) NOT NULL,
    `fee_amount` decimal(18,2) NOT NULL DEFAULT 0.00,
    `seller_income` decimal(18,2) NOT NULL DEFAULT 0.00,
    `order_status` varchar(24) NOT NULL DEFAULT 'CREATE_PENDING',
    `pay_status` varchar(24) NOT NULL DEFAULT 'INIT',
    `delivery_status` varchar(24) NOT NULL DEFAULT 'WAIT_DELIVERY',
    `escrow_status` varchar(24) NOT NULL DEFAULT 'NONE',
    `dispute_status` varchar(24) NOT NULL DEFAULT 'NONE',
    `payment_no` varchar(64) DEFAULT NULL,
    `pay_deadline` datetime(3) DEFAULT NULL,
    `delivery_deadline` datetime(3) DEFAULT NULL,
    `delivered_time` datetime(3) DEFAULT NULL,
    `confirmed_time` datetime(3) DEFAULT NULL,
    `auto_confirm_time` datetime(3) DEFAULT NULL,
    `settle_available_time` datetime(3) DEFAULT NULL,
    `settled_time` datetime(3) DEFAULT NULL,
    `version` int NOT NULL DEFAULT 0,
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_no` (`order_no`),
    KEY `idx_buyer_status` (`buyer_id`, `order_status`),
    KEY `idx_seller_status` (`seller_id`, `order_status`),
    KEY `idx_auto_confirm` (`order_status`, `auto_confirm_time`),
    KEY `idx_settle_time` (`escrow_status`, `settle_available_time`),
    KEY `idx_pay_deadline` (`order_status`, `pay_deadline`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='虚拟资产担保交易订单表';

CREATE TABLE IF NOT EXISTS `t_trade_order_status_log` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `order_no` varchar(64) NOT NULL,
    `from_status` varchar(32) DEFAULT NULL,
    `to_status` varchar(32) NOT NULL,
    `status_type` varchar(32) NOT NULL,
    `operator_type` varchar(16) NOT NULL,
    `operator_id` bigint unsigned DEFAULT NULL,
    `reason` varchar(255) DEFAULT NULL,
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    KEY `idx_order_no` (`order_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='担保订单状态流转日志表';

CREATE TABLE IF NOT EXISTS `t_payment_order` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `payment_no` varchar(64) NOT NULL,
    `order_no` varchar(64) NOT NULL,
    `channel` varchar(24) NOT NULL,
    `amount` decimal(18,2) NOT NULL,
    `status` varchar(24) NOT NULL DEFAULT 'INIT',
    `callback_token_hash` char(64) DEFAULT NULL,
    `callback_secret_version` int NOT NULL DEFAULT 1,
    `expire_time` datetime(3) NOT NULL,
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_payment_no` (`payment_no`),
    UNIQUE KEY `uk_order_no` (`order_no`),
    KEY `idx_status_expire` (`status`, `expire_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Mock支付单表';

CREATE TABLE IF NOT EXISTS `t_payment_callback` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `callback_no` varchar(64) NOT NULL,
    `payment_no` varchar(64) NOT NULL,
    `result` varchar(16) NOT NULL,
    `amount` decimal(18,2) NOT NULL,
    `signature` varchar(128) NOT NULL,
    `raw_payload` text NOT NULL,
    `timestamp_epoch_ms` bigint NOT NULL DEFAULT 0,
    `nonce` varchar(64) NOT NULL DEFAULT '',
    `signature_algorithm` varchar(32) NOT NULL DEFAULT 'HMAC-SHA256',
    `secret_version` int NOT NULL DEFAULT 1,
    `verify_status` varchar(24) NOT NULL DEFAULT 'PENDING',
    `failure_reason` varchar(128) DEFAULT NULL,
    `received_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_callback_no` (`callback_no`),
    UNIQUE KEY `uk_payment_nonce` (`payment_no`, `nonce`),
    KEY `idx_payment_no` (`payment_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Mock支付回调记录表';

CREATE TABLE IF NOT EXISTS `t_payment_callback_nonce` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `nonce` varchar(64) NOT NULL,
    `payment_no` varchar(64) DEFAULT NULL,
    `secret_version` int NOT NULL DEFAULT 1,
    `expire_time` datetime(3) NOT NULL,
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_nonce` (`nonce`),
    KEY `idx_expire_time` (`expire_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='支付回调防重放表';

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

CREATE TABLE IF NOT EXISTS `t_delivery_record` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `order_no` varchar(64) NOT NULL,
    `delivery_type` varchar(24) NOT NULL,
    `delivery_content` text,
    `card_secret_ids` json,
    `status` varchar(24) NOT NULL DEFAULT 'DELIVERED',
    `delivered_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `first_view_time` datetime(3) DEFAULT NULL,
    `confirmed_time` datetime(3) DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_no` (`order_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='交易交付记录表';

CREATE TABLE IF NOT EXISTS `t_delivery_evidence` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `order_no` varchar(64) NOT NULL,
    `evidence_type` varchar(16) NOT NULL,
    `file_url` varchar(512) DEFAULT NULL,
    `content` text,
    `file_hash` char(64) DEFAULT NULL,
    `uploader_type` varchar(16) NOT NULL,
    `uploader_id` bigint unsigned DEFAULT NULL,
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    KEY `idx_order_no` (`order_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='交付证据表';

CREATE TABLE IF NOT EXISTS `t_dispute` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `dispute_no` varchar(64) NOT NULL,
    `order_no` varchar(64) NOT NULL,
    `buyer_id` bigint unsigned NOT NULL,
    `seller_id` bigint unsigned NOT NULL,
    `dispute_type` varchar(32) NOT NULL,
    `reason` varchar(1024) NOT NULL,
    `proposed_refund_amount` decimal(18,2) NOT NULL DEFAULT 0.00,
    `status` varchar(24) NOT NULL DEFAULT 'OPEN',
    `deadline_time` datetime(3) DEFAULT NULL,
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_dispute_no` (`dispute_no`),
    UNIQUE KEY `uk_order_no` (`order_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='交易售后争议表';

CREATE TABLE IF NOT EXISTS `t_dispute_message` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `dispute_no` varchar(64) NOT NULL,
    `sender_type` varchar(16) NOT NULL,
    `sender_id` bigint unsigned DEFAULT NULL,
    `message` varchar(2048) NOT NULL,
    `attachment_url` varchar(512) DEFAULT NULL,
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    KEY `idx_dispute_no` (`dispute_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='售后争议留言表';

CREATE TABLE IF NOT EXISTS `t_arbitration` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `dispute_no` varchar(64) NOT NULL,
    `order_no` varchar(64) NOT NULL,
    `result` varchar(32) NOT NULL,
    `refund_amount` decimal(18,2) NOT NULL DEFAULT 0.00,
    `release_amount` decimal(18,2) NOT NULL DEFAULT 0.00,
    `deposit_deduct_amount` decimal(18,2) NOT NULL DEFAULT 0.00,
    `reason` varchar(1024) NOT NULL,
    `arbitrator_id` bigint unsigned NOT NULL,
    `appeal_status` varchar(16) NOT NULL DEFAULT 'NONE',
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_dispute_no` (`dispute_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='售后仲裁结果表';

CREATE TABLE IF NOT EXISTS `t_order_settlement` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `order_no` varchar(64) NOT NULL,
    `order_amount` decimal(18,2) NOT NULL,
    `fee_amount` decimal(18,2) NOT NULL,
    `seller_income` decimal(18,2) NOT NULL,
    `status` varchar(24) NOT NULL DEFAULT 'PENDING',
    `fund_transaction_no` varchar(64) DEFAULT NULL,
    `settled_time` datetime(3) DEFAULT NULL,
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_no` (`order_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单结算表';

CREATE TABLE IF NOT EXISTS `t_trade_reconciliation_diff` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `diff_key` varchar(128) NOT NULL,
    `diff_type` varchar(64) NOT NULL,
    `biz_no` varchar(64) NOT NULL,
    `expected_amount` decimal(18,2) NOT NULL DEFAULT 0.00,
    `actual_amount` decimal(18,2) NOT NULL DEFAULT 0.00,
    `status` varchar(16) NOT NULL DEFAULT 'OPEN',
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_diff_key` (`diff_key`),
    KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='担保交易对账差异表';

CREATE TABLE IF NOT EXISTS `t_order_review` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `order_no` varchar(64) NOT NULL,
    `buyer_id` bigint unsigned NOT NULL,
    `merchant_id` bigint unsigned NOT NULL,
    `score` tinyint NOT NULL,
    `content` varchar(1024) DEFAULT NULL,
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_no` (`order_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单评价表';

CREATE TABLE IF NOT EXISTS `t_trade_config` (
    `config_key` varchar(64) NOT NULL,
    `config_value` varchar(255) NOT NULL,
    `description` varchar(255) DEFAULT NULL,
    `updated_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`config_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='担保交易配置表';

INSERT INTO `t_trade_config` (`config_key`, `config_value`, `description`) VALUES
    ('payment.expire.minutes', '15', '支付超时时间'),
    ('delivery.timeout.minutes', '30', '卖家发货超时时间'),
    ('auto.confirm.hours', '24', '买家自动确认时间'),
    ('settle.cooldown.hours', '24', '确认后结算冷却期'),
    ('withdraw.min.amount', '10.00', '最低提现金额'),
    ('withdraw.daily.limit', '5000.00', '单日最高提现金额')
ON DUPLICATE KEY UPDATE `config_value` = VALUES(`config_value`);
