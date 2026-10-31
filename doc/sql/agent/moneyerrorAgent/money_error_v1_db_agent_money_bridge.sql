-- 资金异常调查 Agent 增量：money_error_v1_db_agent_money_bridge.sql
-- 依据：doc/agent/moneyErrorAgent/data_contracts_and_schema.md
-- 目标：MySQL 8.0 / db_agent；阶段 M3。客服桥接增量；已有客服基础迁移须先就绪。
-- 使用前阅读同目录 README.md；本次只生成文件，未执行数据库迁移。
-- CREATE IF NOT EXISTS 仅跳过同名表，不检查或修复结构漂移。
-- 业务时间由应用显式写 UTC；不保存凭据，不建立跨服务外键。
-- 本脚本不更改已有支付/账务记录，不自动改旧表索引或开启业务功能。

SET NAMES utf8mb4;
SET time_zone = '+00:00';
USE `db_agent`;

-- t_agent_money_binding
CREATE TABLE IF NOT EXISTS t_agent_money_binding (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  case_no VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  task_no VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  remote_context_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  order_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  audience_scope VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  origin_session_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  current_session_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  grant_ref VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  case_state VARCHAR(24) NOT NULL,
  task_state VARCHAR(24) NOT NULL,
  subscription_state VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
  projection_revision BIGINT NOT NULL DEFAULT 0,
  projection_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  projection_json JSON NULL,
  next_poll_at DATETIME(3) NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_agent_money_binding (user_id, case_no, audience_scope),
  KEY idx_agent_money_poll (subscription_state, case_state, next_poll_at, id),
  KEY idx_agent_money_order (user_id, order_no, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- t_agent_money_dispatch
CREATE TABLE IF NOT EXISTS t_agent_money_dispatch (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  dispatch_no VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  user_id BIGINT NOT NULL,
  session_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  run_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  intent_instance_id VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NULL,
  action VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  request_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  payload_json JSON NOT NULL,
  grant_ref VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  state VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  remote_task_no VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NULL,
  receipt_json JSON NULL,
  attempts INT NOT NULL,
  next_attempt_at DATETIME(3) NOT NULL,
  lease_until DATETIME(3) NULL,
  lease_owner VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  fencing_token BIGINT NOT NULL,
  last_error VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_agent_money_dispatch_1 (dispatch_no),
  UNIQUE KEY uk_agent_money_dispatch_2 (user_id,action,request_id),
  KEY idx_agent_money_dispatch_3 (state,next_attempt_at,lease_until,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- t_agent_money_delivery
CREATE TABLE IF NOT EXISTS t_agent_money_delivery (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  delivery_no VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  case_no VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  projection_revision BIGINT NOT NULL,
  recipient_user_id BIGINT NOT NULL,
  delivery_kind VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  session_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  message_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  expected_control_epoch BIGINT NULL,
  state VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  payload_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  next_attempt_at DATETIME(3) NULL,
  last_error VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_agent_money_delivery_1 (delivery_no),
  UNIQUE KEY uk_agent_money_delivery_2 (case_no,projection_revision,recipient_user_id,delivery_kind),
  KEY idx_agent_money_delivery_3 (state,next_attempt_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- t_agent_money_card
CREATE TABLE IF NOT EXISTS t_agent_money_card (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  card_id VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  session_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  user_id BIGINT NOT NULL,
  case_no VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  input_no VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NULL,
  card_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  revision BIGINT NOT NULL,
  projection_revision BIGINT NOT NULL,
  allowed_actions_json JSON NOT NULL,
  public_card_json JSON NOT NULL,
  state VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  expires_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_agent_money_card_1 (card_id),
  KEY idx_agent_money_card_2 (session_no,case_no,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- t_agent_money_notice_inbox
CREATE TABLE IF NOT EXISTS t_agent_money_notice_inbox (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  source VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  event_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  payload_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  payload_json JSON NOT NULL,
  state VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  next_attempt_at DATETIME(3) NOT NULL,
  attempts INT NOT NULL,
  last_error VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_agent_money_notice_inbox_1 (source,event_id),
  KEY idx_agent_money_notice_inbox_2 (state,next_attempt_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- t_agent_money_staff_note
CREATE TABLE IF NOT EXISTS t_agent_money_staff_note (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  case_no VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  projection_revision BIGINT NOT NULL,
  session_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  assigned_staff_id BIGINT NULL,
  public_for_staff_json JSON NOT NULL,
  based_on_control_epoch BIGINT NOT NULL,
  created_at DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_agent_money_staff_note_1 (case_no,projection_revision,session_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
