CREATE DATABASE IF NOT EXISTS `db_risk` DEFAULT CHARACTER SET utf8mb4;

USE `db_risk`;

CREATE TABLE IF NOT EXISTS `t_risk_subject` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `subject_type` varchar(16) NOT NULL COMMENT '主体类型: USER, MERCHANT, ITEM, ORDER, WITHDRAW',
    `subject_id` bigint unsigned NOT NULL,
    `risk_status` varchar(16) NOT NULL DEFAULT 'NORMAL' COMMENT 'NORMAL, WATCH, VERIFY, LIMITED, MANUAL_REVIEW, FROZEN',
    `risk_level` varchar(16) NOT NULL DEFAULT 'LOW',
    `risk_score` int NOT NULL DEFAULT 0,
    `last_decision_no` varchar(64) DEFAULT NULL,
    `risk_reason` varchar(255) DEFAULT NULL,
    `restricted_until` datetime(3) DEFAULT NULL,
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_subject` (`subject_type`, `subject_id`),
    KEY `idx_risk_status` (`risk_status`, `updated_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='风控主体最新状态表';

CREATE TABLE IF NOT EXISTS `t_risk_rule` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `rule_code` varchar(64) NOT NULL,
    `rule_name` varchar(128) NOT NULL,
    `scene` varchar(32) NOT NULL,
    `expression_json` json NOT NULL,
    `action` varchar(16) NOT NULL,
    `risk_score` int NOT NULL DEFAULT 0,
    `auto_case` tinyint NOT NULL DEFAULT 0,
    `enabled` tinyint NOT NULL DEFAULT 1,
    `version` int NOT NULL DEFAULT 1,
    `remark` varchar(255) DEFAULT NULL,
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_rule_code` (`rule_code`),
    KEY `idx_scene_enabled` (`scene`, `enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='风控规则表';

CREATE TABLE IF NOT EXISTS `t_risk_rule_change_log` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `rule_id` bigint unsigned NOT NULL,
    `rule_code` varchar(64) NOT NULL,
    `change_type` varchar(16) NOT NULL COMMENT 'CREATE, UPDATE, ENABLE, DISABLE',
    `before_snapshot` json DEFAULT NULL,
    `after_snapshot` json DEFAULT NULL,
    `operator_id` bigint unsigned NOT NULL,
    `operator_name` varchar(64) NOT NULL,
    `reason` varchar(255) NOT NULL,
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    KEY `idx_rule_id` (`rule_id`, `created_time`),
    KEY `idx_operator` (`operator_id`, `created_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='风控规则变更日志表';

CREATE TABLE IF NOT EXISTS `t_risk_event` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `event_no` varchar(64) NOT NULL,
    `scene` varchar(32) NOT NULL,
    `event_type` varchar(32) NOT NULL,
    `event_phase` varchar(16) NOT NULL DEFAULT 'PRECHECK' COMMENT 'PRECHECK, CONFIRMED, INVALID',
    `biz_type` varchar(32) DEFAULT NULL,
    `biz_no` varchar(64) DEFAULT NULL,
    `user_id` bigint unsigned DEFAULT NULL,
    `merchant_id` bigint unsigned DEFAULT NULL,
    `item_id` bigint unsigned DEFAULT NULL,
    `order_no` varchar(64) DEFAULT NULL,
    `withdraw_no` varchar(64) DEFAULT NULL,
    `amount` decimal(18,2) DEFAULT NULL,
    `ip_hash` char(64) DEFAULT NULL,
    `device_hash` char(64) DEFAULT NULL,
    `payload_json` json DEFAULT NULL,
    `request_hash` char(64) NOT NULL,
    `occurred_time` datetime(3) NOT NULL,
    `confirmed_time` datetime(3) DEFAULT NULL,
    `invalidated_time` datetime(3) DEFAULT NULL,
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_event_no` (`event_no`),
    KEY `idx_phase_scene_time` (`event_phase`, `scene`, `occurred_time`),
    KEY `idx_user_phase_time` (`user_id`, `event_phase`, `occurred_time`),
    KEY `idx_merchant_phase_time` (`merchant_id`, `event_phase`, `occurred_time`),
    KEY `idx_biz` (`biz_type`, `biz_no`),
    KEY `idx_ip_hash` (`ip_hash`, `event_phase`, `occurred_time`),
    KEY `idx_device_hash` (`device_hash`, `event_phase`, `occurred_time`),
    KEY `idx_invalid_expire` (`event_phase`, `occurred_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='风控事件表';

CREATE TABLE IF NOT EXISTS `t_risk_decision` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `decision_no` varchar(64) NOT NULL,
    `event_no` varchar(64) NOT NULL,
    `scene` varchar(32) NOT NULL,
    `biz_type` varchar(32) DEFAULT NULL,
    `biz_no` varchar(64) DEFAULT NULL,
    `action` varchar(16) NOT NULL,
    `risk_score` int NOT NULL DEFAULT 0,
    `risk_level` varchar(16) NOT NULL,
    `hit_rules_json` json NOT NULL,
    `metric_snapshot_json` json NOT NULL,
    `evidence_json` json DEFAULT NULL,
    `missing_metrics_json` json DEFAULT NULL,
    `degraded` tinyint NOT NULL DEFAULT 0,
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_decision_no` (`decision_no`),
    UNIQUE KEY `uk_event_no` (`event_no`),
    KEY `idx_scene_action_time` (`scene`, `action`, `created_time`),
    KEY `idx_biz` (`biz_type`, `biz_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='风控决策表';

CREATE TABLE IF NOT EXISTS `t_risk_case` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `case_no` varchar(64) NOT NULL,
    `dedup_key` varchar(128) NOT NULL,
    `decision_no` varchar(64) NOT NULL,
    `scene` varchar(32) NOT NULL,
    `biz_type` varchar(32) DEFAULT NULL,
    `biz_no` varchar(64) DEFAULT NULL,
    `subject_type` varchar(16) NOT NULL,
    `subject_id` bigint unsigned NOT NULL,
    `risk_level` varchar(16) NOT NULL,
    `risk_score` int NOT NULL DEFAULT 0,
    `status` varchar(16) NOT NULL DEFAULT 'OPEN',
    `assigned_to` bigint unsigned DEFAULT NULL,
    `resolved_action` varchar(32) DEFAULT NULL,
    `resolve_reason` varchar(255) DEFAULT NULL,
    `resolved_time` datetime(3) DEFAULT NULL,
    `last_command_no` varchar(64) DEFAULT NULL,
    `last_command_json` json DEFAULT NULL,
    `command_status` varchar(24) NOT NULL DEFAULT 'NONE' COMMENT 'NONE, SENT, SUCCESS, FAILED, COMMAND_FAILED',
    `command_retry_count` int NOT NULL DEFAULT 0,
    `reopen_count` int NOT NULL DEFAULT 0,
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_case_no` (`case_no`),
    UNIQUE KEY `uk_dedup_key` (`dedup_key`),
    KEY `idx_last_command_no` (`last_command_no`),
    KEY `idx_status_scene_time` (`status`, `scene`, `created_time`),
    KEY `idx_biz` (`biz_type`, `biz_no`),
    KEY `idx_assigned` (`assigned_to`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='风控案件表';

CREATE TABLE IF NOT EXISTS `t_risk_case_note` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `case_no` varchar(64) NOT NULL,
    `note_type` varchar(24) NOT NULL COMMENT 'CREATE, ASSIGN, PROCESS, RESOLVE, CLOSE, REOPEN, COMMAND_SUCCESS, COMMAND_FAILED, COMMAND_DEAD_LETTER',
    `operator_id` bigint unsigned NOT NULL,
    `operator_name` varchar(64) NOT NULL,
    `content` varchar(1024) NOT NULL,
    `before_status` varchar(16) DEFAULT NULL,
    `after_status` varchar(16) DEFAULT NULL,
    `evidence_json` json DEFAULT NULL,
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    KEY `idx_case_no` (`case_no`, `created_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='风控案件处理记录表';

CREATE TABLE IF NOT EXISTS `t_user_device` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `user_id` bigint unsigned NOT NULL,
    `device_hash` char(64) NOT NULL,
    `first_seen_time` datetime(3) NOT NULL,
    `last_seen_time` datetime(3) NOT NULL,
    `hit_count` int NOT NULL DEFAULT 1,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_device` (`user_id`, `device_hash`),
    KEY `idx_device_time` (`device_hash`, `last_seen_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户与设备哈希关系表';

CREATE TABLE IF NOT EXISTS `t_user_ip` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `user_id` bigint unsigned NOT NULL,
    `ip_hash` char(64) NOT NULL,
    `first_seen_time` datetime(3) NOT NULL,
    `last_seen_time` datetime(3) NOT NULL,
    `hit_count` int NOT NULL DEFAULT 1,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_ip` (`user_id`, `ip_hash`),
    KEY `idx_ip_time` (`ip_hash`, `last_seen_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户与IP哈希关系表';

CREATE TABLE IF NOT EXISTS `t_relation_edge` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `source_user_id` bigint unsigned NOT NULL,
    `target_user_id` bigint unsigned NOT NULL,
    `relation_type` varchar(32) NOT NULL,
    `weight` int NOT NULL DEFAULT 0,
    `hit_count` int NOT NULL DEFAULT 1,
    `first_seen_time` datetime(3) NOT NULL,
    `last_seen_time` datetime(3) NOT NULL,
    `evidence_json` json NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_relation` (`source_user_id`, `target_user_id`, `relation_type`),
    KEY `idx_source_type_time` (`source_user_id`, `relation_type`, `last_seen_time`),
    KEY `idx_target_type_time` (`target_user_id`, `relation_type`, `last_seen_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户关系边表';

CREATE TABLE IF NOT EXISTS `t_sensitive_word` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `word_code` varchar(64) NOT NULL,
    `category` varchar(32) NOT NULL,
    `word` varchar(64) NOT NULL,
    `enabled` tinyint NOT NULL DEFAULT 1,
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_category_word` (`category`, `word`),
    KEY `idx_enabled_category` (`enabled`, `category`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='敏感词表';

CREATE TABLE IF NOT EXISTS `t_risk_indicator` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `metric_code` varchar(64) NOT NULL,
    `subject_type` varchar(16) NOT NULL,
    `subject_id` bigint unsigned NOT NULL,
    `window_type` varchar(16) NOT NULL COMMENT 'REALTIME, 10M, 1H, 24H, 7D, 30D, TOTAL',
    `window_start` datetime(3) NOT NULL,
    `window_end` datetime(3) NOT NULL,
    `metric_value` decimal(18,4) NOT NULL,
    `updated_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_indicator` (`metric_code`, `subject_type`, `subject_id`, `window_type`, `window_start`),
    KEY `idx_subject_window` (`subject_type`, `subject_id`, `window_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='风控指标物化表';

INSERT INTO `t_sensitive_word` (`word_code`, `category`, `word`, `enabled`) VALUES
('ILLEGAL_VIRTUAL_COIN', 'ILLEGAL_ASSET', '虚拟货币', 1),
('ILLEGAL_RECHARGE', 'ILLEGAL_ASSET', '代充', 1),
('ILLEGAL_CHEAT', 'ILLEGAL_ASSET', '外挂', 1),
('ILLEGAL_STOLEN_ACCOUNT', 'ILLEGAL_ASSET', '盗号账号', 1),
('OFF_WECHAT', 'OFF_PLATFORM_CONTACT', '微信', 1),
('OFF_QQ', 'OFF_PLATFORM_CONTACT', 'qq', 1),
('OFF_TELEGRAM', 'OFF_PLATFORM_CONTACT', 'telegram', 1),
('PRIVATE_OFFLINE', 'PRIVATE_TRANSACTION', '私下交易', 1),
('PRIVATE_BYPASS', 'PRIVATE_TRANSACTION', '绕过平台', 1),
('PRIVATE_PLATFORM_GUARANTEE', 'PRIVATE_TRANSACTION', '平台外担保', 1),
('RECOVERY_ACCOUNT', 'ACCOUNT_RECOVERY', '找回账号', 1),
('RECOVERY_EMAIL', 'ACCOUNT_RECOVERY', '原始邮箱', 1),
('RECOVERY_ID_CHANGE', 'ACCOUNT_RECOVERY', '身份证可改', 1),
('FRAUD_STABLE_PROFIT', 'FRAUD_HINT', '稳赚', 1),
('FRAUD_GUARANTEE_PASS', 'FRAUD_HINT', '包过', 1),
('FRAUD_MONEY_LAUNDERING', 'FRAUD_HINT', '洗黑产', 1),
('FRAUD_COLLECT_MONEY', 'FRAUD_HINT', '代收', 1)
ON DUPLICATE KEY UPDATE `enabled` = VALUES(`enabled`);

INSERT INTO `t_risk_rule`
(`rule_code`, `rule_name`, `scene`, `expression_json`, `action`, `risk_score`, `auto_case`, `enabled`, `remark`)
VALUES
('REGISTER_SAME_IP_BATCH', '同IP批量注册', 'REGISTER', '{"all":[{"metric":"REGISTER_SAME_IP_COUNT_10M","operator":"GTE","value":5}]}', 'MANUAL_REVIEW', 70, 1, 1, '同IP10分钟注册至少5个账号'),
('REGISTER_SAME_DEVICE_BATCH', '同设备批量注册', 'REGISTER', '{"all":[{"metric":"REGISTER_SAME_DEVICE_COUNT_30D","operator":"GTE","value":3}]}', 'MANUAL_REVIEW', 80, 1, 1, '同设备30天关联至少3个账号'),
('LOGIN_FAIL_TOO_MANY', '登录失败频控', 'LOGIN', '{"all":[{"metric":"LOGIN_FAIL_COUNT_10M","operator":"GTE","value":5}]}', 'LIMIT', 50, 0, 1, '10分钟失败至少5次'),
('MERCHANT_RISK_PROFILE', '商家申请高风险描述', 'MERCHANT', '{"any":[{"metric":"SENSITIVE_WORD_CATEGORY","operator":"IN","value":["ACCOUNT_RECOVERY","FRAUD_HINT"]}]}', 'MANUAL_REVIEW', 70, 1, 1, '申请信息命中高风险词'),
('LISTING_ILLEGAL_ASSET', '禁售虚拟资产', 'LISTING', '{"all":[{"metric":"SENSITIVE_WORD_CATEGORY","operator":"EQ","value":"ILLEGAL_ASSET"}]}', 'REJECT', 100, 0, 1, '命中禁售资产敏感词'),
('LISTING_OFF_PLATFORM_CONTACT', '站外联系方式', 'LISTING', '{"all":[{"metric":"SENSITIVE_WORD_CATEGORY","operator":"EQ","value":"OFF_PLATFORM_CONTACT"}]}', 'REJECT', 90, 0, 1, '商品信息包含站外联系方式'),
('LISTING_PRIVATE_TRANSACTION', '私下交易诱导', 'LISTING', '{"all":[{"metric":"SENSITIVE_WORD_CATEGORY","operator":"EQ","value":"PRIVATE_TRANSACTION"}]}', 'REJECT', 90, 0, 1, '诱导绕过平台交易'),
('LISTING_LOW_PRICE_NEW_MERCHANT', '新商家低价挂售', 'LISTING', '{"all":[{"metric":"ITEM_PRICE_DEVIATION","operator":"LTE","value":-0.5},{"metric":"MERCHANT_COMPLETED_COUNT","operator":"LT","value":3}]}', 'MANUAL_REVIEW', 70, 1, 1, '价格低于类目中位数50%且完成订单少于3'),
('LISTING_HIGH_PRICE_NEW_MERCHANT', '新商家高额挂售', 'LISTING', '{"all":[{"metric":"MERCHANT_AGE_HOURS","operator":"LT","value":72},{"metric":"CURRENT_AMOUNT","operator":"GTE","value":5000}]}', 'MANUAL_REVIEW', 70, 1, 1, '新商家高额挂售；P95倍数可在表达式可扩展后调整'),
('ORDER_SELF_TRADE', '关联账号自买自卖', 'ORDER', '{"any":[{"metric":"BUYER_SELLER_RELATION","operator":"IN","value":["SAME_USER","SAME_DEVICE","SAME_IP"]}]}', 'FREEZE', 90, 1, 1, '买卖双方同用户、同设备或强IP关联'),
('ORDER_HIGH_AMOUNT_NEW_BUYER', '新买家高额下单', 'ORDER', '{"all":[{"metric":"USER_COMPLETED_COUNT","operator":"LT","value":3},{"metric":"CURRENT_AMOUNT","operator":"GTE","value":1000}]}', 'MANUAL_REVIEW', 70, 1, 1, '完成订单少于3且金额至少1000'),
('ORDER_HIGH_FREQUENCY', '买家高频下单', 'ORDER', '{"all":[{"metric":"USER_ORDER_COUNT_10M","operator":"GTE","value":5}]}', 'REJECT', 80, 0, 1, '买家10分钟下单至少5次'),
('ORDER_MERCHANT_REFUND_RATE', '商家退款率过高', 'ORDER', '{"all":[{"metric":"MERCHANT_REFUND_RATE_30D","operator":"GTE","value":0.2},{"metric":"MERCHANT_COMPLETED_COUNT","operator":"GTE","value":5}]}', 'LIMIT', 70, 1, 1, '30天退款率至少20%且完成订单至少5'),
('PAYMENT_BEFORE_REVIEW', '风险订单禁止支付', 'PAYMENT', '{"all":[{"metric":"ORDER_RISK_STATUS","operator":"NE","value":"NORMAL"}]}', 'REJECT', 80, 0, 1, '非正常风险状态订单禁止支付'),
('DELIVERY_TOO_FAST_MANUAL', '人工交付过快', 'DELIVERY', '{"all":[{"metric":"ORDER_PAY_TO_DELIVER_SECONDS","operator":"LTE","value":5}]}', 'WATCH', 30, 0, 1, '支付后5秒内人工交付'),
('CONFIRM_TOO_FAST', '确认收货过快', 'CONFIRM', '{"all":[{"metric":"ORDER_VIEW_TO_CONFIRM_SECONDS","operator":"LTE","value":30}]}', 'WATCH', 30, 0, 1, '首次查看后30秒内确认'),
('DISPUTE_BUYER_ABUSE', '买家频繁争议', 'DISPUTE', '{"all":[{"metric":"USER_DISPUTE_COUNT_7D","operator":"GTE","value":3}]}', 'MANUAL_REVIEW', 70, 1, 1, '买家7天争议至少3次'),
('WITHDRAW_NEW_MERCHANT_HIGH', '新商家大额提现', 'WITHDRAW', '{"all":[{"metric":"MERCHANT_AGE_HOURS","operator":"LT","value":72},{"metric":"WITHDRAW_AMOUNT","operator":"GTE","value":1000}]}', 'MANUAL_REVIEW', 70, 1, 1, '商家账龄小于72小时且金额至少1000'),
('WITHDRAW_FAST_OUT', '结算后快进快出', 'WITHDRAW', '{"all":[{"metric":"MERCHANT_SETTLE_TO_WITHDRAW_MINUTES","operator":"LTE","value":60},{"metric":"WITHDRAW_AMOUNT","operator":"GTE","value":500}]}', 'MANUAL_REVIEW', 70, 1, 1, '结算可用后60分钟内提现且金额至少500'),
('WITHDRAW_SAME_ACCOUNT', '同提现账号多商家', 'WITHDRAW', '{"all":[{"metric":"WITHDRAW_ACCOUNT_MERCHANT_COUNT","operator":"GTE","value":3}]}', 'FREEZE', 90, 1, 1, '同Mock提现账号关联至少3个商家'),
('WITHDRAW_DAILY_LIMIT', '商家当日提现超限', 'WITHDRAW', '{"all":[{"metric":"MERCHANT_WITHDRAW_AMOUNT_24H","operator":"GTE","value":5000}]}', 'REJECT', 80, 0, 1, '单商家当日累计提现至少5000')
ON DUPLICATE KEY UPDATE `rule_name` = VALUES(`rule_name`), `expression_json` = VALUES(`expression_json`),
    `action` = VALUES(`action`), `risk_score` = VALUES(`risk_score`), `auto_case` = VALUES(`auto_case`),
    `enabled` = VALUES(`enabled`), `remark` = VALUES(`remark`);

USE `db_item`;
ALTER TABLE `t_item`
    ADD COLUMN `risk_status` varchar(16) NOT NULL DEFAULT 'NORMAL'
        COMMENT 'NORMAL, WATCH, VERIFY, LIMITED, MANUAL_REVIEW, FROZEN, REJECTED',
    ADD COLUMN `risk_level` varchar(16) NOT NULL DEFAULT 'LOW',
    ADD COLUMN `risk_decision_no` varchar(64) DEFAULT NULL,
    ADD COLUMN `risk_reason` varchar(255) DEFAULT NULL;

ALTER TABLE `t_item`
    ADD INDEX `idx_risk_status` (`risk_status`, `status`);

USE `db_order`;
ALTER TABLE `t_trade_order`
    ADD COLUMN `risk_status` varchar(16) NOT NULL DEFAULT 'NORMAL'
        COMMENT 'NORMAL, WATCH, VERIFY, LIMITED, MANUAL_REVIEW, FROZEN, REJECTED',
    ADD COLUMN `risk_level` varchar(16) NOT NULL DEFAULT 'LOW',
    ADD COLUMN `risk_decision_no` varchar(64) DEFAULT NULL,
    ADD COLUMN `risk_reason` varchar(255) DEFAULT NULL;

ALTER TABLE `t_trade_order`
    ADD INDEX `idx_risk_order_status` (`risk_status`, `order_status`);

USE `db_user`;
ALTER TABLE `t_merchant`
    ADD COLUMN `risk_status` varchar(16) NOT NULL DEFAULT 'NORMAL'
        COMMENT 'NORMAL, WATCH, VERIFY, LIMITED, MANUAL_REVIEW, FROZEN, REJECTED',
    ADD COLUMN `risk_level` varchar(16) NOT NULL DEFAULT 'LOW',
    ADD COLUMN `risk_decision_no` varchar(64) DEFAULT NULL,
    ADD COLUMN `risk_reason` varchar(255) DEFAULT NULL;

ALTER TABLE `t_withdraw_request`
    ADD COLUMN `risk_status` varchar(16) NOT NULL DEFAULT 'NORMAL'
        COMMENT 'NORMAL, WATCH, VERIFY, LIMITED, MANUAL_REVIEW, FROZEN, REJECTED',
    ADD COLUMN `risk_level` varchar(16) NOT NULL DEFAULT 'LOW',
    ADD COLUMN `risk_decision_no` varchar(64) DEFAULT NULL,
    ADD COLUMN `risk_reason` varchar(255) DEFAULT NULL,
    ADD COLUMN `payout_delay_until` datetime(3) DEFAULT NULL;
