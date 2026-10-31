-- 资金异常调查 Agent 增量：money_error_v2_money_repair_command.sql
-- 依据：doc/agent/moneyErrorAgent/data_contracts_and_schema.md
-- 目标：MySQL 8.0 / db_order；阶段 M5。后续受控修复命令。仅在相关一致性验收通过后使用；本文件不执行修复。
-- 使用前阅读同目录 README.md；本次只生成文件，未执行数据库迁移。
-- CREATE IF NOT EXISTS 仅跳过同名表，不检查或修复结构漂移。
-- 业务时间由应用显式写 UTC；不保存凭据，不建立跨服务外键。
-- 本脚本不更改已有支付/账务记录，不自动改旧表索引或开启业务功能。

SET NAMES utf8mb4;
SET time_zone = '+00:00';
USE `db_order`;

-- t_money_repair_command
CREATE TABLE IF NOT EXISTS t_money_repair_command (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  operation_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  proposal_no VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  action_type VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  scope_key VARCHAR(192) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  params_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  params_json JSON NOT NULL,
  approval_ref VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  expected_versions_json JSON NOT NULL,
  state VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  business_task_ref VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  result_json JSON NULL,
  attempt_count INT NOT NULL,
  next_run_at DATETIME(3) NULL,
  lease_owner VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  lease_until DATETIME(3) NULL,
  fencing_token BIGINT NOT NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_money_repair_command_1 (operation_id),
  UNIQUE KEY uk_money_repair_command_2 (proposal_no),
  KEY idx_money_repair_command_3 (state,next_run_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
