-- 支付回调安全阶段一数据库变更。
-- 当前项目未接入 Flyway/Liquibase，请在发布前按环境手工执行一次。

ALTER TABLE `t_payment_order`
    ADD COLUMN `callback_secret_version` int NOT NULL DEFAULT 1 COMMENT '创建支付单时回调密钥版本' AFTER `callback_token_hash`;

ALTER TABLE `t_payment_callback`
    ADD COLUMN `timestamp_epoch_ms` bigint NOT NULL DEFAULT 0 COMMENT '回调时间戳' AFTER `raw_payload`,
    ADD COLUMN `nonce` varchar(64) NOT NULL DEFAULT '' COMMENT '回调随机数' AFTER `timestamp_epoch_ms`,
    ADD COLUMN `signature_algorithm` varchar(32) NOT NULL DEFAULT 'HMAC-SHA256' COMMENT '签名算法' AFTER `nonce`,
    ADD COLUMN `secret_version` int NOT NULL DEFAULT 1 COMMENT '密钥版本' AFTER `signature_algorithm`,
    ADD COLUMN `verify_status` varchar(24) NOT NULL DEFAULT 'PENDING' COMMENT '校验状态' AFTER `secret_version`,
    ADD COLUMN `failure_reason` varchar(128) DEFAULT NULL COMMENT '校验失败原因' AFTER `verify_status`,
    ADD UNIQUE KEY `uk_payment_nonce` (`payment_no`, `nonce`);

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
