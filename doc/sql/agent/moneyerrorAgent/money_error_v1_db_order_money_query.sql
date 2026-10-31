-- 资金异常调查 Agent 增量：money_error_v1_db_order_money_query.sql
-- 依据：doc/agent/moneyErrorAgent/data_contracts_and_schema.md
-- 目标：MySQL 8.0 / db_order；阶段 M1/M4。渠道只读观察与订单事件 Outbox；建表不启用事件发布。
-- 使用前阅读同目录 README.md；本次只生成文件，未执行数据库迁移。
-- CREATE IF NOT EXISTS 仅跳过同名表，不检查或修复结构漂移。
-- 业务时间由应用显式写 UTC；不保存凭据，不建立跨服务外键。
-- 本脚本不更改已有支付/账务记录，不自动改旧表索引或开启业务功能。

SET NAMES utf8mb4;
SET time_zone = '+00:00';
USE `db_order`;

-- t_money_channel_observation
CREATE TABLE IF NOT EXISTS t_money_channel_observation (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  observation_no VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  scope_key VARCHAR(192) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  attempt_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  refund_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  profile_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  profile_version INT NOT NULL,
  request_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  observation_json JSON NOT NULL,
  result_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  observed_at DATETIME(3) NOT NULL,
  created_at DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_money_channel_observation_1 (observation_no),
  KEY idx_money_channel_observation_2 (attempt_no,observed_at,id),
  KEY idx_money_channel_observation_3 (refund_no,observed_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- t_money_domain_outbox
CREATE TABLE IF NOT EXISTS t_money_domain_outbox (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  event_no VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  destination VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  payload_json JSON NOT NULL,
  state VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  attempt_count INT NOT NULL,
  next_attempt_at DATETIME(3) NOT NULL,
  lease_owner VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  lease_until DATETIME(3) NULL,
  fencing_token BIGINT NOT NULL,
  last_error VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_money_domain_outbox_1 (event_no,destination),
  KEY idx_money_domain_outbox_2 (state,next_attempt_at,lease_until,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
