-- 资金异常调查 Agent 增量：money_error_v1_db_money_agent_core.sql
-- 依据：doc/agent/moneyErrorAgent/data_contracts_and_schema.md
-- 目标：MySQL 8.0 / db_money_agent；阶段 M2/M4。调查运行时。action_proposal 仅保存建议，建表不启用资金操作。
-- 使用前阅读同目录 README.md；本次只生成文件，未执行数据库迁移。
-- CREATE IF NOT EXISTS 仅跳过同名表，不检查或修复结构漂移。
-- 业务时间由应用显式写 UTC；不保存凭据，不建立跨服务外键。
-- 本脚本不更改已有支付/账务记录，不自动改旧表索引或开启业务功能。

SET NAMES utf8mb4;
SET time_zone = '+00:00';
CREATE DATABASE IF NOT EXISTS `db_money_agent` DEFAULT CHARACTER SET utf8mb4;
USE `db_money_agent`;

-- t_money_case
CREATE TABLE IF NOT EXISTS t_money_case (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  case_no VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  scope_key VARCHAR(192) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  problem_family VARCHAR(32) NOT NULL,
  target_ref VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  active_dedupe_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  previous_case_no VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NULL,
  order_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  origin VARCHAR(24) NOT NULL,
  state VARCHAR(24) NOT NULL DEFAULT 'OPEN',
  outcome VARCHAR(32) NOT NULL DEFAULT 'UNDETERMINED',
  severity VARCHAR(16) NOT NULL DEFAULT 'NORMAL',
  owner_staff_id BIGINT NULL,
  current_task_no VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NULL,
  latest_report_no VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NULL,
  grant_ref VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  event_seq BIGINT NOT NULL DEFAULT 0,
  version BIGINT NOT NULL DEFAULT 0,
  close_reason VARCHAR(512) NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  closed_at DATETIME(3) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_money_case_no (case_no),
  UNIQUE KEY uk_money_case_active (active_dedupe_key),
  KEY idx_money_case_order (order_no, state, id),
  KEY idx_money_case_worklist (state, severity, updated_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- t_money_task
CREATE TABLE IF NOT EXISTS t_money_task (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  task_no VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  case_no VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  context_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  execution_grant_ref VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  execution_scope_json JSON NOT NULL,
  execution_scope_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  state VARCHAR(24) NOT NULL DEFAULT 'SUBMITTED',
  wait_reason VARCHAR(32) NULL,
  checkpoint_json JSON NULL,
  input_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  next_run_at DATETIME(3) NULL,
  lease_owner VARCHAR(64) NULL,
  lease_until DATETIME(3) NULL,
  fencing_token BIGINT NOT NULL DEFAULT 0,
  plan_rounds INT NOT NULL DEFAULT 0,
  tool_calls INT NOT NULL DEFAULT 0,
  input_tokens BIGINT NOT NULL DEFAULT 0,
  output_tokens BIGINT NOT NULL DEFAULT 0,
  cost_microunits BIGINT NOT NULL DEFAULT 0,
  version BIGINT NOT NULL DEFAULT 0,
  last_error_code VARCHAR(64) NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  finished_at DATETIME(3) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_money_task_no (task_no),
  KEY idx_money_task_case (case_no, id),
  KEY idx_money_task_claim (state, next_run_at, lease_until, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- t_money_request
CREATE TABLE IF NOT EXISTS t_money_request (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  caller_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  action VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  request_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  payload_json JSON NOT NULL,
  subject_scope_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  grant_ref VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  case_no VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NULL,
  task_no VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NULL,
  state VARCHAR(24) NOT NULL,
  receipt_json JSON NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_money_request (caller_id, action, request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- t_money_grant
CREATE TABLE IF NOT EXISTS t_money_grant (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  grant_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  issuer VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  subject_type VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  subject_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  scope_json JSON NOT NULL,
  allowed_actions_json JSON NOT NULL,
  allowed_receivers_json JSON NOT NULL,
  policy_version VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  state VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  expires_at DATETIME(3) NOT NULL,
  revoked_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_money_grant_1 (grant_no),
  KEY idx_money_grant_2 (state,expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- t_money_task_access
CREATE TABLE IF NOT EXISTS t_money_task_access (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  task_no VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  grant_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  caller_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  audience_scope VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  state VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  expires_at DATETIME(3) NOT NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_money_task_access_1 (task_no,grant_no,audience_scope),
  KEY idx_money_task_access_2 (grant_no,state),
  KEY idx_money_task_access_3 (task_no,state)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- t_money_task_input
CREATE TABLE IF NOT EXISTS t_money_task_input (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  task_no VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  input_seq BIGINT NOT NULL,
  request_row_id BIGINT NOT NULL,
  input_kind VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  input_request_no VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NULL,
  grant_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  payload_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  content_json JSON NOT NULL,
  created_at DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_money_task_input_1 (task_no,input_seq),
  UNIQUE KEY uk_money_task_input_2 (task_no,request_row_id),
  KEY idx_money_task_input_3 (task_no,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- t_money_snapshot
CREATE TABLE IF NOT EXISTS t_money_snapshot (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  snapshot_no VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  task_no VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  step_no INT NOT NULL,
  source_kind VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  coverage_json JSON NOT NULL,
  facts_json JSON NOT NULL,
  observed_at DATETIME(3) NOT NULL,
  created_at DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_money_snapshot_1 (snapshot_no),
  UNIQUE KEY uk_money_snapshot_2 (task_no,step_no,source_kind),
  KEY idx_money_snapshot_3 (task_no,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- t_money_report
CREATE TABLE IF NOT EXISTS t_money_report (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  report_no VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  case_no VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  task_no VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  revision BIGINT NOT NULL,
  schema_version INT NOT NULL,
  based_on_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  internal_json JSON NOT NULL,
  report_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  outcome VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  rule_version VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  prompt_version VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  model_version VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  created_at DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_money_report_1 (report_no),
  UNIQUE KEY uk_money_report_2 (case_no,revision),
  KEY idx_money_report_3 (task_no,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- t_money_projection
CREATE TABLE IF NOT EXISTS t_money_projection (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  case_no VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  audience_scope VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  revision BIGINT NOT NULL,
  report_no VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NULL,
  public_json JSON NOT NULL,
  content_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  latest_case_event_seq BIGINT NOT NULL,
  policy_version VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_money_projection_1 (case_no,audience_scope),
  KEY idx_money_projection_2 (updated_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- t_money_input_request
CREATE TABLE IF NOT EXISTS t_money_input_request (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  input_no VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  case_no VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  task_no VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  audience_scope VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  input_version INT NOT NULL,
  state VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  schema_json JSON NOT NULL,
  answer_json JSON NULL,
  answer_source_ref VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
  expires_at DATETIME(3) NOT NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_money_input_request_1 (input_no),
  KEY idx_money_input_request_2 (task_no,state),
  KEY idx_money_input_request_3 (state,expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- t_money_event
CREATE TABLE IF NOT EXISTS t_money_event (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  event_no VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  case_no VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  case_seq BIGINT NOT NULL,
  task_no VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NULL,
  event_type VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  payload_ref_json JSON NOT NULL,
  visibility VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  created_at DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_money_event_1 (event_no),
  UNIQUE KEY uk_money_event_2 (case_no,case_seq),
  KEY idx_money_event_3 (case_no,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- t_money_tool_call
CREATE TABLE IF NOT EXISTS t_money_tool_call (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  task_no VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  call_seq INT NOT NULL,
  tool_name VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  target_ref VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  input_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  fact_ref_json JSON NULL,
  outcome VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  error_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  latency_ms INT NOT NULL,
  created_at DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_money_tool_call_1 (task_no,call_seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- t_money_action_proposal
CREATE TABLE IF NOT EXISTS t_money_action_proposal (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  proposal_no VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  case_no VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  report_no VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  action_type VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  target_ref VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  params_json JSON NOT NULL,
  params_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  evidence_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  expected_versions_json JSON NOT NULL,
  state VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  proposer_ref VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  reviewer_id BIGINT NULL,
  review_reason VARCHAR(512) NULL,
  reviewed_at DATETIME(3) NULL,
  expires_at DATETIME(3) NOT NULL,
  operation_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_money_action_proposal_1 (proposal_no),
  UNIQUE KEY uk_money_action_proposal_2 (operation_id),
  KEY idx_money_action_proposal_3 (case_no,state,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- t_money_source_inbox
CREATE TABLE IF NOT EXISTS t_money_source_inbox (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  source VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  event_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  payload_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  aggregate_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  aggregate_ref VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  aggregate_version VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  schema_version INT NOT NULL,
  payload_json JSON NOT NULL,
  state VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  attempt_count INT NOT NULL,
  next_attempt_at DATETIME(3) NOT NULL,
  last_error VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_money_source_inbox_1 (source,event_id),
  KEY idx_money_source_inbox_2 (state,next_attempt_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- t_money_outbox
CREATE TABLE IF NOT EXISTS t_money_outbox (
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
  UNIQUE KEY uk_money_outbox_1 (event_no,destination),
  KEY idx_money_outbox_2 (state,next_attempt_at,lease_until,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
