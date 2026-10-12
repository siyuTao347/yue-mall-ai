-- 第三批运营闭环增量迁移
-- 生产执行前请确认字段/索引不存在，优先使用 MySQL 8 在线 DDL。

USE db_risk;

ALTER TABLE `t_risk_case`
    ADD COLUMN `command_last_error` varchar(512) DEFAULT NULL COMMENT '命令最近失败原因(脱敏摘要)',
    ADD COLUMN `command_sent_time` datetime(3) DEFAULT NULL COMMENT '命令最近发送时间',
    ADD COLUMN `command_finished_time` datetime(3) DEFAULT NULL COMMENT '命令最近完成时间',
    ADD COLUMN `operation_version` bigint unsigned NOT NULL DEFAULT 0 COMMENT '操作版本号，用于关键操作并发保护',
    ADD KEY `idx_command_status_time` (`command_status`, `updated_time`);
