-- 资金异常调查 Agent：客服意图种子（MySQL 8.0 / db_agent，M3）
-- 前置：customerServiceAgent/stage3_customer_service_intent_mysql_migration.sql 已建意图与示例表。
-- 全部新增意图与示例均为 DISABLED；不激活意图库，不写 PG，不生成虚假向量。
-- 只插入缺失主键，不覆盖管理员维护的已有记录；整段种子在本地事务内执行。
-- 当前意图表无 optional_slots_json；可选槽位由 IntentCatalog/CustomerSlotRegistry 实现。
-- 启用需先完成 MONEY_BRIDGE、槽位、输出校验与联调，再走 IntentReleaseService 发布。

SET NAMES utf8mb4;
USE `db_agent`;
START TRANSACTION;

INSERT INTO t_customer_service_intent
  (intent_code,intent_name,description,route_node,required_slots_json,intent_version,status)
VALUES
  ('PAYMENT_ANOMALY','支付资金异常','支付渠道扣款与平台订单状态不一致、多次扣款或订单关闭后扣款，普通支付状态查询仍走订单意图','MONEY_BRIDGE',JSON_ARRAY('orderNo'),1,'DISABLED')
ON DUPLICATE KEY UPDATE intent_code = intent_code;

INSERT INTO t_customer_service_intent_example
  (example_no,example_version,intent_code,intent_version,example_text,normalized_text,example_hash,status,embedding_status)
SELECT 'MONEY-PAYMENT_ANOMALY-001',1,i.intent_code,i.intent_version,'微信扣款了但订单还是待支付','微信扣款了但订单还是待支付',SHA2('微信扣款了但订单还是待支付',256),'DISABLED','PENDING'
FROM t_customer_service_intent i
WHERE i.intent_code = 'PAYMENT_ANOMALY' AND i.intent_version = 1 AND i.route_node = 'MONEY_BRIDGE'
ON DUPLICATE KEY UPDATE example_no = example_no;

INSERT INTO t_customer_service_intent_example
  (example_no,example_version,intent_code,intent_version,example_text,normalized_text,example_hash,status,embedding_status)
SELECT 'MONEY-PAYMENT_ANOMALY-002',1,i.intent_code,i.intent_version,'同一笔订单好像被扣了两次','同一笔订单好像被扣了两次',SHA2('同一笔订单好像被扣了两次',256),'DISABLED','PENDING'
FROM t_customer_service_intent i
WHERE i.intent_code = 'PAYMENT_ANOMALY' AND i.intent_version = 1 AND i.route_node = 'MONEY_BRIDGE'
ON DUPLICATE KEY UPDATE example_no = example_no;

INSERT INTO t_customer_service_intent_example
  (example_no,example_version,intent_code,intent_version,example_text,normalized_text,example_hash,status,embedding_status)
SELECT 'MONEY-PAYMENT_ANOMALY-003',1,i.intent_code,i.intent_version,'订单已经关闭了支付渠道却显示扣款成功','订单已经关闭了支付渠道却显示扣款成功',SHA2('订单已经关闭了支付渠道却显示扣款成功',256),'DISABLED','PENDING'
FROM t_customer_service_intent i
WHERE i.intent_code = 'PAYMENT_ANOMALY' AND i.intent_version = 1 AND i.route_node = 'MONEY_BRIDGE'
ON DUPLICATE KEY UPDATE example_no = example_no;

INSERT INTO t_customer_service_intent
  (intent_code,intent_name,description,route_node,required_slots_json,intent_version,status)
VALUES
  ('REFUND_ANOMALY','退款资金异常','退款记录与渠道状态冲突或持续未确认，申请退款流程和售后单进度仍由售后意图负责','MONEY_BRIDGE',JSON_ARRAY('orderNo'),1,'DISABLED')
ON DUPLICATE KEY UPDATE intent_code = intent_code;

INSERT INTO t_customer_service_intent_example
  (example_no,example_version,intent_code,intent_version,example_text,normalized_text,example_hash,status,embedding_status)
SELECT 'MONEY-REFUND_ANOMALY-001',1,i.intent_code,i.intent_version,'订单显示退款了但钱还没退回来','订单显示退款了但钱还没退回来',SHA2('订单显示退款了但钱还没退回来',256),'DISABLED','PENDING'
FROM t_customer_service_intent i
WHERE i.intent_code = 'REFUND_ANOMALY' AND i.intent_version = 1 AND i.route_node = 'MONEY_BRIDGE'
ON DUPLICATE KEY UPDATE example_no = example_no;

INSERT INTO t_customer_service_intent_example
  (example_no,example_version,intent_code,intent_version,example_text,normalized_text,example_hash,status,embedding_status)
SELECT 'MONEY-REFUND_ANOMALY-002',1,i.intent_code,i.intent_version,'退款记录和支付渠道显示的状态不一致','退款记录和支付渠道显示的状态不一致',SHA2('退款记录和支付渠道显示的状态不一致',256),'DISABLED','PENDING'
FROM t_customer_service_intent i
WHERE i.intent_code = 'REFUND_ANOMALY' AND i.intent_version = 1 AND i.route_node = 'MONEY_BRIDGE'
ON DUPLICATE KEY UPDATE example_no = example_no;

INSERT INTO t_customer_service_intent_example
  (example_no,example_version,intent_code,intent_version,example_text,normalized_text,example_hash,status,embedding_status)
SELECT 'MONEY-REFUND_ANOMALY-003',1,i.intent_code,i.intent_version,'这笔退款一直没有确认结果请帮我核查','这笔退款一直没有确认结果请帮我核查',SHA2('这笔退款一直没有确认结果请帮我核查',256),'DISABLED','PENDING'
FROM t_customer_service_intent i
WHERE i.intent_code = 'REFUND_ANOMALY' AND i.intent_version = 1 AND i.route_node = 'MONEY_BRIDGE'
ON DUPLICATE KEY UPDATE example_no = example_no;

INSERT INTO t_customer_service_intent
  (intent_code,intent_name,description,route_node,required_slots_json,intent_version,status)
VALUES
  ('MONEY_CASE_PROGRESS','资金调查进度','查询已存在的资金调查，缺少案件范围时先澄清，不直接创建新的调查','MONEY_BRIDGE',JSON_ARRAY('moneyCaseNo'),1,'DISABLED')
ON DUPLICATE KEY UPDATE intent_code = intent_code;

INSERT INTO t_customer_service_intent_example
  (example_no,example_version,intent_code,intent_version,example_text,normalized_text,example_hash,status,embedding_status)
SELECT 'MONEY-MONEY_CASE_PROGRESS-001',1,i.intent_code,i.intent_version,'刚才那个资金调查有结果了吗','刚才那个资金调查有结果了吗',SHA2('刚才那个资金调查有结果了吗',256),'DISABLED','PENDING'
FROM t_customer_service_intent i
WHERE i.intent_code = 'MONEY_CASE_PROGRESS' AND i.intent_version = 1 AND i.route_node = 'MONEY_BRIDGE'
ON DUPLICATE KEY UPDATE example_no = example_no;

INSERT INTO t_customer_service_intent_example
  (example_no,example_version,intent_code,intent_version,example_text,normalized_text,example_hash,status,embedding_status)
SELECT 'MONEY-MONEY_CASE_PROGRESS-002',1,i.intent_code,i.intent_version,'帮我查一下这笔订单的资金调查进度','帮我查一下这笔订单的资金调查进度',SHA2('帮我查一下这笔订单的资金调查进度',256),'DISABLED','PENDING'
FROM t_customer_service_intent i
WHERE i.intent_code = 'MONEY_CASE_PROGRESS' AND i.intent_version = 1 AND i.route_node = 'MONEY_BRIDGE'
ON DUPLICATE KEY UPDATE example_no = example_no;

INSERT INTO t_customer_service_intent_example
  (example_no,example_version,intent_code,intent_version,example_text,normalized_text,example_hash,status,embedding_status)
SELECT 'MONEY-MONEY_CASE_PROGRESS-003',1,i.intent_code,i.intent_version,'我已经提交的资金核查现在到哪一步了','我已经提交的资金核查现在到哪一步了',SHA2('我已经提交的资金核查现在到哪一步了',256),'DISABLED','PENDING'
FROM t_customer_service_intent i
WHERE i.intent_code = 'MONEY_CASE_PROGRESS' AND i.intent_version = 1 AND i.route_node = 'MONEY_BRIDGE'
ON DUPLICATE KEY UPDATE example_no = example_no;

COMMIT;

-- 新增预期：3 个 DISABLED 意图、9 个 DISABLED/PENDING 示例。
-- 已有同编码记录不覆盖；若被跳过，启用前核对路由、槽位和版本。
