-- 资金异常调查 Agent：迁移后只读核对（MySQL 8.0）
-- 在所需阶段脚本执行后运行；不写数据，不修改结构。
-- M5 表未实施时允许显示 MISSING；对应阶段的其他 MISSING 需要处理。
-- 列/索引展示供比对，不等同于已验证应用事务、权限、并发与运行正确性。

WITH expected_tables AS (
  SELECT 'db_money_agent' AS db_name, 't_money_case' AS table_name, 'M2/M4' AS phase
  UNION ALL
  SELECT 'db_money_agent' AS db_name, 't_money_task' AS table_name, 'M2/M4' AS phase
  UNION ALL
  SELECT 'db_money_agent' AS db_name, 't_money_request' AS table_name, 'M2/M4' AS phase
  UNION ALL
  SELECT 'db_money_agent' AS db_name, 't_money_grant' AS table_name, 'M2/M4' AS phase
  UNION ALL
  SELECT 'db_money_agent' AS db_name, 't_money_task_access' AS table_name, 'M2/M4' AS phase
  UNION ALL
  SELECT 'db_money_agent' AS db_name, 't_money_task_input' AS table_name, 'M2/M4' AS phase
  UNION ALL
  SELECT 'db_money_agent' AS db_name, 't_money_snapshot' AS table_name, 'M2/M4' AS phase
  UNION ALL
  SELECT 'db_money_agent' AS db_name, 't_money_report' AS table_name, 'M2/M4' AS phase
  UNION ALL
  SELECT 'db_money_agent' AS db_name, 't_money_projection' AS table_name, 'M2/M4' AS phase
  UNION ALL
  SELECT 'db_money_agent' AS db_name, 't_money_input_request' AS table_name, 'M2/M4' AS phase
  UNION ALL
  SELECT 'db_money_agent' AS db_name, 't_money_event' AS table_name, 'M2/M4' AS phase
  UNION ALL
  SELECT 'db_money_agent' AS db_name, 't_money_tool_call' AS table_name, 'M2/M4' AS phase
  UNION ALL
  SELECT 'db_money_agent' AS db_name, 't_money_action_proposal' AS table_name, 'M2/M4' AS phase
  UNION ALL
  SELECT 'db_money_agent' AS db_name, 't_money_source_inbox' AS table_name, 'M2/M4' AS phase
  UNION ALL
  SELECT 'db_money_agent' AS db_name, 't_money_outbox' AS table_name, 'M2/M4' AS phase
  UNION ALL
  SELECT 'db_agent' AS db_name, 't_agent_money_binding' AS table_name, 'M3' AS phase
  UNION ALL
  SELECT 'db_agent' AS db_name, 't_agent_money_dispatch' AS table_name, 'M3' AS phase
  UNION ALL
  SELECT 'db_agent' AS db_name, 't_agent_money_delivery' AS table_name, 'M3' AS phase
  UNION ALL
  SELECT 'db_agent' AS db_name, 't_agent_money_card' AS table_name, 'M3' AS phase
  UNION ALL
  SELECT 'db_agent' AS db_name, 't_agent_money_notice_inbox' AS table_name, 'M3' AS phase
  UNION ALL
  SELECT 'db_agent' AS db_name, 't_agent_money_staff_note' AS table_name, 'M3' AS phase
  UNION ALL
  SELECT 'db_order' AS db_name, 't_money_channel_observation' AS table_name, 'M1/M4' AS phase
  UNION ALL
  SELECT 'db_order' AS db_name, 't_money_domain_outbox' AS table_name, 'M1/M4' AS phase
  UNION ALL
  SELECT 'db_user' AS db_name, 't_fund_business_ref' AS table_name, 'M1/M4' AS phase
  UNION ALL
  SELECT 'db_user' AS db_name, 't_money_domain_outbox' AS table_name, 'M1/M4' AS phase
  UNION ALL
  SELECT 'db_order' AS db_name, 't_money_repair_command' AS table_name, 'M5' AS phase
)
SELECT e.db_name, e.table_name, e.phase,
       CASE WHEN t.TABLE_NAME IS NULL THEN 'MISSING' ELSE 'PRESENT' END AS table_check,
       t.ENGINE, t.TABLE_COLLATION
FROM expected_tables e
LEFT JOIN information_schema.TABLES t ON t.TABLE_SCHEMA = e.db_name AND t.TABLE_NAME = e.table_name
ORDER BY e.db_name, e.table_name;

-- 新表字段与默认值；IF NOT EXISTS 无法发现结构漂移，应与对应建表文件逐项核对。
SELECT TABLE_SCHEMA, TABLE_NAME, ORDINAL_POSITION, COLUMN_NAME, COLUMN_TYPE,
       IS_NULLABLE, COLUMN_DEFAULT, COLLATION_NAME, EXTRA
FROM information_schema.COLUMNS
WHERE (TABLE_SCHEMA = 'db_money_agent' AND TABLE_NAME LIKE 't!_money!_%' ESCAPE '!')
   OR (TABLE_SCHEMA = 'db_agent' AND TABLE_NAME LIKE 't!_agent!_money!_%' ESCAPE '!')
   OR (TABLE_SCHEMA = 'db_order' AND TABLE_NAME IN ('t_money_channel_observation','t_money_domain_outbox','t_money_repair_command'))
   OR (TABLE_SCHEMA = 'db_user' AND TABLE_NAME IN ('t_fund_business_ref','t_money_domain_outbox'))
ORDER BY TABLE_SCHEMA, TABLE_NAME, ORDINAL_POSITION;

-- 必需唯一键按完整列序匹配，不依赖索引命名。
WITH expected_keys AS (
  SELECT 'db_money_agent' AS db_name, 't_money_case' AS table_name, 'case_no' AS column_list, 'M2/M4' AS phase
  UNION ALL
  SELECT 'db_money_agent' AS db_name, 't_money_case' AS table_name, 'active_dedupe_key' AS column_list, 'M2/M4' AS phase
  UNION ALL
  SELECT 'db_money_agent' AS db_name, 't_money_task' AS table_name, 'task_no' AS column_list, 'M2/M4' AS phase
  UNION ALL
  SELECT 'db_money_agent' AS db_name, 't_money_request' AS table_name, 'caller_id,action,request_id' AS column_list, 'M2/M4' AS phase
  UNION ALL
  SELECT 'db_money_agent' AS db_name, 't_money_grant' AS table_name, 'grant_no' AS column_list, 'M2/M4' AS phase
  UNION ALL
  SELECT 'db_money_agent' AS db_name, 't_money_task_access' AS table_name, 'task_no,grant_no,audience_scope' AS column_list, 'M2/M4' AS phase
  UNION ALL
  SELECT 'db_money_agent' AS db_name, 't_money_task_input' AS table_name, 'task_no,input_seq' AS column_list, 'M2/M4' AS phase
  UNION ALL
  SELECT 'db_money_agent' AS db_name, 't_money_task_input' AS table_name, 'task_no,request_row_id' AS column_list, 'M2/M4' AS phase
  UNION ALL
  SELECT 'db_money_agent' AS db_name, 't_money_snapshot' AS table_name, 'snapshot_no' AS column_list, 'M2/M4' AS phase
  UNION ALL
  SELECT 'db_money_agent' AS db_name, 't_money_snapshot' AS table_name, 'task_no,step_no,source_kind' AS column_list, 'M2/M4' AS phase
  UNION ALL
  SELECT 'db_money_agent' AS db_name, 't_money_report' AS table_name, 'report_no' AS column_list, 'M2/M4' AS phase
  UNION ALL
  SELECT 'db_money_agent' AS db_name, 't_money_report' AS table_name, 'case_no,revision' AS column_list, 'M2/M4' AS phase
  UNION ALL
  SELECT 'db_money_agent' AS db_name, 't_money_projection' AS table_name, 'case_no,audience_scope' AS column_list, 'M2/M4' AS phase
  UNION ALL
  SELECT 'db_money_agent' AS db_name, 't_money_input_request' AS table_name, 'input_no' AS column_list, 'M2/M4' AS phase
  UNION ALL
  SELECT 'db_money_agent' AS db_name, 't_money_event' AS table_name, 'event_no' AS column_list, 'M2/M4' AS phase
  UNION ALL
  SELECT 'db_money_agent' AS db_name, 't_money_event' AS table_name, 'case_no,case_seq' AS column_list, 'M2/M4' AS phase
  UNION ALL
  SELECT 'db_money_agent' AS db_name, 't_money_tool_call' AS table_name, 'task_no,call_seq' AS column_list, 'M2/M4' AS phase
  UNION ALL
  SELECT 'db_money_agent' AS db_name, 't_money_action_proposal' AS table_name, 'proposal_no' AS column_list, 'M2/M4' AS phase
  UNION ALL
  SELECT 'db_money_agent' AS db_name, 't_money_action_proposal' AS table_name, 'operation_id' AS column_list, 'M2/M4' AS phase
  UNION ALL
  SELECT 'db_money_agent' AS db_name, 't_money_source_inbox' AS table_name, 'source,event_id' AS column_list, 'M2/M4' AS phase
  UNION ALL
  SELECT 'db_money_agent' AS db_name, 't_money_outbox' AS table_name, 'event_no,destination' AS column_list, 'M2/M4' AS phase
  UNION ALL
  SELECT 'db_agent' AS db_name, 't_agent_money_binding' AS table_name, 'user_id,case_no,audience_scope' AS column_list, 'M3' AS phase
  UNION ALL
  SELECT 'db_agent' AS db_name, 't_agent_money_dispatch' AS table_name, 'dispatch_no' AS column_list, 'M3' AS phase
  UNION ALL
  SELECT 'db_agent' AS db_name, 't_agent_money_dispatch' AS table_name, 'user_id,action,request_id' AS column_list, 'M3' AS phase
  UNION ALL
  SELECT 'db_agent' AS db_name, 't_agent_money_delivery' AS table_name, 'delivery_no' AS column_list, 'M3' AS phase
  UNION ALL
  SELECT 'db_agent' AS db_name, 't_agent_money_delivery' AS table_name, 'case_no,projection_revision,recipient_user_id,delivery_kind' AS column_list, 'M3' AS phase
  UNION ALL
  SELECT 'db_agent' AS db_name, 't_agent_money_card' AS table_name, 'card_id' AS column_list, 'M3' AS phase
  UNION ALL
  SELECT 'db_agent' AS db_name, 't_agent_money_notice_inbox' AS table_name, 'source,event_id' AS column_list, 'M3' AS phase
  UNION ALL
  SELECT 'db_agent' AS db_name, 't_agent_money_staff_note' AS table_name, 'case_no,projection_revision,session_no' AS column_list, 'M3' AS phase
  UNION ALL
  SELECT 'db_order' AS db_name, 't_money_channel_observation' AS table_name, 'observation_no' AS column_list, 'M1/M4' AS phase
  UNION ALL
  SELECT 'db_order' AS db_name, 't_money_domain_outbox' AS table_name, 'event_no,destination' AS column_list, 'M1/M4' AS phase
  UNION ALL
  SELECT 'db_user' AS db_name, 't_fund_business_ref' AS table_name, 'transaction_no,ref_type,ref_no' AS column_list, 'M1/M4' AS phase
  UNION ALL
  SELECT 'db_user' AS db_name, 't_money_domain_outbox' AS table_name, 'event_no,destination' AS column_list, 'M1/M4' AS phase
  UNION ALL
  SELECT 'db_order' AS db_name, 't_money_repair_command' AS table_name, 'operation_id' AS column_list, 'M5' AS phase
  UNION ALL
  SELECT 'db_order' AS db_name, 't_money_repair_command' AS table_name, 'proposal_no' AS column_list, 'M5' AS phase
), actual_keys AS (
  SELECT TABLE_SCHEMA AS db_name, TABLE_NAME AS table_name, INDEX_NAME,
         GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX SEPARATOR ',') AS column_list,
         SUM(CASE WHEN SUB_PART IS NOT NULL THEN 1 ELSE 0 END) AS prefix_parts
  FROM information_schema.STATISTICS
  WHERE NON_UNIQUE = 0 AND TABLE_SCHEMA IN ('db_money_agent','db_agent','db_order','db_user')
  GROUP BY TABLE_SCHEMA, TABLE_NAME, INDEX_NAME
)
SELECT e.db_name, e.table_name, e.column_list, e.phase,
       CASE WHEN EXISTS (
         SELECT 1 FROM actual_keys a WHERE a.db_name=e.db_name
           AND a.table_name=e.table_name AND a.column_list=e.column_list AND a.prefix_parts=0
       ) THEN 'PRESENT' ELSE 'MISSING' END AS unique_key_check
FROM expected_keys e
ORDER BY e.db_name,e.table_name,e.column_list;

-- 以下查询要求客服意图基础表已存在；首次导入预期 3 个 DISABLED 定义。
SELECT intent_code, route_node, required_slots_json, intent_version, status
FROM db_agent.t_customer_service_intent
WHERE intent_code IN ('PAYMENT_ANOMALY','REFUND_ANOMALY','MONEY_CASE_PROGRESS')
ORDER BY intent_code;

-- 首次导入预期每类 3 条 DISABLED/PENDING 示例，bad_hashes=0；后续启用后状态可变化。
SELECT intent_code, status, embedding_status, COUNT(*) AS example_count,
       SUM(CASE WHEN example_hash <> SHA2(normalized_text,256) THEN 1 ELSE 0 END) AS bad_hashes
FROM db_agent.t_customer_service_intent_example
WHERE example_no LIKE 'MONEY-%' AND intent_code IN ('PAYMENT_ANOMALY','REFUND_ANOMALY','MONEY_CASE_PROGRESS')
GROUP BY intent_code,status,embedding_status
ORDER BY intent_code,status,embedding_status;

-- 调查源表现有索引。只读取元数据；不要未经 EXPLAIN 就重复增加复合索引。
SELECT TABLE_SCHEMA,TABLE_NAME,INDEX_NAME,NON_UNIQUE,
       GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX SEPARATOR ',') AS column_list
FROM information_schema.STATISTICS
WHERE (TABLE_SCHEMA='db_order' AND TABLE_NAME IN ('t_payment_attempt','t_payment_refund_order','t_payment_financial_event','t_recon_diff'))
   OR (TABLE_SCHEMA='db_user' AND TABLE_NAME='t_fund_flow')
GROUP BY TABLE_SCHEMA,TABLE_NAME,INDEX_NAME,NON_UNIQUE
ORDER BY TABLE_SCHEMA,TABLE_NAME,INDEX_NAME;
