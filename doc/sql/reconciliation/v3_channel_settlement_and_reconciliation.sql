-- ==============================================================================
-- v3_channel_settlement_and_reconciliation.sql
-- 支付多渠道演进：金融事件子账、结算单、到账回执、日历、Mock账单及多层对账核心表
-- 适用数据库：db_order
-- ==============================================================================

USE `db_order`;

-- 1. 平台侧不可变资金事实/预期表 t_payment_financial_event
CREATE TABLE IF NOT EXISTS `t_payment_financial_event` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `event_no` varchar(64) NOT NULL COMMENT '金融事件唯一编号',
    `profile_id` varchar(64) NOT NULL COMMENT '渠道商户协议ID',
    `profile_version` int NOT NULL DEFAULT 1 COMMENT '协议版本',
    `channel_merchant_no` varchar(64) NOT NULL COMMENT '渠道商户号',
    `account_id` varchar(64) NOT NULL COMMENT '所属结算账户',
    `event_type` varchar(32) NOT NULL COMMENT '类型: PAYMENT/REFUND_ACCEPTED/REFUND_SUCCEEDED/FEE/FEE_RETURN/ADJUSTMENT',
    `event_stage` varchar(32) NOT NULL COMMENT '阶段: OCCURRED/CLEARED/SETTLED',
    `biz_type` varchar(32) NOT NULL COMMENT '业务类型: ORDER/SUSPENSE/MANUAL',
    `biz_no` varchar(64) NOT NULL COMMENT '业务编号(attempt_no/refund_no)',
    `order_no` varchar(64) DEFAULT NULL COMMENT '订单号',
    `amount` decimal(18,2) NOT NULL COMMENT '发生金额',
    `fee_amount` decimal(18,2) NOT NULL DEFAULT 0.00 COMMENT '相关手续费',
    `currency` varchar(8) NOT NULL DEFAULT 'CNY' COMMENT '币种',
    `channel_business_date` date NOT NULL COMMENT '清算业务归属日期',
    `occurred_at` datetime(3) NOT NULL COMMENT '业务发生时间',
    `recorded_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '写库记录时间',
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_event_no` (`event_no`),
    UNIQUE KEY `uk_prof_merch_type_biz_stage` (`profile_id`, `channel_merchant_no`, `event_type`, `biz_no`, `event_stage`),
    KEY `idx_date_account` (`channel_business_date`, `account_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='平台支付金融事件子账';

-- 2. 渠道结算单 t_channel_settlement_order
CREATE TABLE IF NOT EXISTS `t_channel_settlement_order` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `settlement_no` varchar(64) NOT NULL COMMENT '结算批次单号',
    `profile_id` varchar(64) NOT NULL COMMENT '协议ID',
    `channel_merchant_no` varchar(64) NOT NULL COMMENT '渠道商户号',
    `account_id` varchar(64) NOT NULL COMMENT '渠道结算账户',
    `business_date` date NOT NULL COMMENT '结算批次归属清算业务日期',
    `settlement_target` varchar(32) NOT NULL COMMENT '结算目标 BANK/ECNY_WALLET',
    `target_account` varchar(64) NOT NULL COMMENT '到账目标银行卡号/钱包ID',
    `plan_version` int NOT NULL DEFAULT 1 COMMENT '结算计算版本',
    `gross_amount` decimal(18,2) NOT NULL DEFAULT 0.00 COMMENT '交易借方汇总(实收总额)',
    `refund_amount` decimal(18,2) NOT NULL DEFAULT 0.00 COMMENT '退款贷方汇总(退款总额)',
    `fee_amount` decimal(18,2) NOT NULL DEFAULT 0.00 COMMENT '渠道手续费汇总',
    `fee_return_amount` decimal(18,2) NOT NULL DEFAULT 0.00 COMMENT '退费汇总',
    `net_amount` decimal(18,2) NOT NULL DEFAULT 0.00 COMMENT '实际应结净额',
    `status` varchar(24) NOT NULL DEFAULT 'PLANNED' COMMENT '状态: PLANNED/DUE/PROCESSING/SUCCEEDED/UNKNOWN/WAIT_FUNDS/FAILED',
    `expected_settled_at` datetime(3) NOT NULL COMMENT '预期到账时间',
    `actual_settled_at` datetime(3) DEFAULT NULL COMMENT '实际确认到账时间',
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    `updated_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_settlement_no` (`settlement_no`),
    UNIQUE KEY `uk_prof_merch_acc_date_target_ver` (`profile_id`, `channel_merchant_no`, `account_id`, `business_date`, `settlement_target`, `plan_version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='渠道结算批次单';

-- 3. 渠道结算明细行 t_channel_settlement_line
CREATE TABLE IF NOT EXISTS `t_channel_settlement_line` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `settlement_no` varchar(64) NOT NULL COMMENT '结算批次单号',
    `financial_event_no` varchar(64) NOT NULL COMMENT '平台金融事件编号',
    `settlement_purpose` varchar(32) NOT NULL COMMENT '用途: TRADE_SETTLEMENT/SUSPENSE_CLEARING',
    `direction` varchar(8) NOT NULL COMMENT '方向: CREDIT(+)/DEBIT(-)',
    `amount` decimal(18,2) NOT NULL COMMENT '金融事件发生额',
    `fee` decimal(18,2) NOT NULL DEFAULT 0.00 COMMENT '对应扣费',
    `fee_return` decimal(18,2) NOT NULL DEFAULT 0.00 COMMENT '对应退费',
    `expected_net` decimal(18,2) NOT NULL COMMENT '单笔净结算贡献',
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_settle_event` (`settlement_no`, `financial_event_no`),
    UNIQUE KEY `uk_event_purpose` (`financial_event_no`, `settlement_purpose`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='渠道结算批次包含明细行';

-- 4. 平台观察到的模拟银行/钱包实际到账回执 t_channel_cash_receipt
CREATE TABLE IF NOT EXISTS `t_channel_cash_receipt` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `receipt_no` varchar(64) NOT NULL COMMENT '到账回执编号',
    `source` varchar(32) NOT NULL COMMENT '来源: BANK/ECNY_WALLET',
    `account_id` varchar(64) NOT NULL COMMENT '实际到账的平台银行基本户/钱包账户',
    `settlement_no` varchar(64) DEFAULT NULL COMMENT '关联的渠道结算单号',
    `transfer_no` varchar(64) DEFAULT NULL COMMENT '关联的资金调拨单号(如数币兑回)',
    `amount` decimal(18,2) NOT NULL COMMENT '到账金额',
    `currency` varchar(8) NOT NULL DEFAULT 'CNY' COMMENT '币种',
    `value_date` date NOT NULL COMMENT '银行起息日期',
    `observed_at` datetime(3) NOT NULL COMMENT '观察/拉取到流水时间',
    `raw_details` text COMMENT '回执原始快照JSON',
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_source_acc_receipt` (`source`, `account_id`, `receipt_no`),
    KEY `idx_settlement_no` (`settlement_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='渠道结算到账回执表';

-- 5. 跨账户资金调拨单 t_channel_transfer (数币钱包兑回银行等)
CREATE TABLE IF NOT EXISTS `t_channel_transfer` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `transfer_no` varchar(64) NOT NULL COMMENT '调拨业务编号',
    `transfer_type` varchar(32) NOT NULL COMMENT '调拨类型: ECNY_REDEEM',
    `from_account` varchar(64) NOT NULL COMMENT '源账户',
    `to_account` varchar(64) NOT NULL COMMENT '目标账户',
    `amount` decimal(18,2) NOT NULL COMMENT '调拨金额',
    `currency` varchar(8) NOT NULL DEFAULT 'CNY' COMMENT '币种',
    `request_key` varchar(128) NOT NULL COMMENT '调拨幂等键',
    `status` varchar(24) NOT NULL DEFAULT 'SUBMITTED' COMMENT '状态: SUBMITTED/PROCESSING/SUCCEEDED/FAILED',
    `channel_trade_no` varchar(64) DEFAULT NULL COMMENT '外部回执流水',
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    `updated_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_transfer_no` (`transfer_no`),
    UNIQUE KEY `uk_source_req_key` (`from_account`, `request_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='资金账户间调拨表';

-- 6. 结算日历工作日配置表 t_settlement_calendar_day
CREATE TABLE IF NOT EXISTS `t_settlement_calendar_day` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `calendar_id` varchar(64) NOT NULL COMMENT '日历标识(如 MOCK_CN_ACQUIRER)',
    `cal_date` date NOT NULL COMMENT '自然日期',
    `is_settlement_day` tinyint NOT NULL DEFAULT 1 COMMENT '是否结算有效日 1=工作结算日 0=非结算日(周末/节假日)',
    `version` int NOT NULL DEFAULT 1 COMMENT '日历版本',
    `reason` varchar(128) DEFAULT NULL COMMENT '原因说明(如 周末/国庆等)',
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_cal_date_ver` (`calendar_id`, `cal_date`, `version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='收单结算工作日历表';

-- 7. Mock 渠道独立结算转账动作记录 t_mock_settlement_transfer
CREATE TABLE IF NOT EXISTS `t_mock_settlement_transfer` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `mock_settlement_no` varchar(64) NOT NULL COMMENT '模拟结算转账编号',
    `profile_id` varchar(64) NOT NULL COMMENT '协议ID',
    `channel_merchant_no` varchar(64) NOT NULL COMMENT '渠道商户号',
    `business_date` date NOT NULL COMMENT '清算业务日期',
    `target_account` varchar(64) NOT NULL COMMENT '目标到账账户',
    `version` int NOT NULL DEFAULT 1 COMMENT '版本',
    `net_amount` decimal(18,2) NOT NULL COMMENT '实际划拨净额',
    `planned_time` datetime(3) NOT NULL COMMENT '计划划出时间',
    `actual_time` datetime(3) DEFAULT NULL COMMENT '实际划出时间',
    `status` varchar(24) NOT NULL DEFAULT 'SUCCEEDED' COMMENT '划出状态 SUCCEEDED/FAILED',
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_mock_settlement_no` (`mock_settlement_no`),
    UNIQUE KEY `uk_mock_prof_merch_date_target_ver` (`profile_id`, `channel_merchant_no`, `business_date`, `target_account`, `version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Mock渠道独立结算记录表';

-- 8. 独立模拟银行流水明细 t_mock_bank_entry
CREATE TABLE IF NOT EXISTS `t_mock_bank_entry` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `bank_entry_no` varchar(64) NOT NULL COMMENT '银行流水明细号',
    `external_reference` varchar(64) NOT NULL COMMENT '关联外部结算/调拨单号',
    `entry_seq` int NOT NULL DEFAULT 1 COMMENT '拆分到账序号',
    `account_id` varchar(64) NOT NULL COMMENT '银行基本户',
    `amount` decimal(18,2) NOT NULL COMMENT '入账金额',
    `direction` varchar(8) NOT NULL DEFAULT 'CREDIT' COMMENT '借贷 CREDIT(+)/DEBIT(-)',
    `value_date` date NOT NULL COMMENT '起息日期',
    `entry_time` datetime(3) NOT NULL COMMENT '到账时间',
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_bank_entry_no` (`bank_entry_no`),
    UNIQUE KEY `uk_ext_ref_seq` (`external_reference`, `entry_seq`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='独立模拟银行到账流水表';

-- 9. 渠道独立日账单元数据与快照 t_mock_statement
CREATE TABLE IF NOT EXISTS `t_mock_statement` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `scope_id` varchar(32) NOT NULL DEFAULT 'DEFAULT' COMMENT '测试隔离作用域',
    `profile_id` varchar(64) NOT NULL COMMENT '协议ID',
    `channel_merchant_no` varchar(64) NOT NULL COMMENT '渠道商户号',
    `account_id` varchar(64) NOT NULL COMMENT '资金账户',
    `statement_type` varchar(32) NOT NULL COMMENT '账单类型: TRADE/FUND/SETTLEMENT',
    `business_date` date NOT NULL COMMENT '业务日期',
    `version` int NOT NULL DEFAULT 1 COMMENT '账单版本',
    `file_hash` varchar(64) NOT NULL COMMENT '账单文件SHA-256哈希',
    `row_count` int NOT NULL DEFAULT 0 COMMENT '总明细行数',
    `total_pay_amount` decimal(18,2) NOT NULL DEFAULT 0.00 COMMENT '支付总额',
    `total_refund_amount` decimal(18,2) NOT NULL DEFAULT 0.00 COMMENT '退款总额',
    `total_fee_amount` decimal(18,2) NOT NULL DEFAULT 0.00 COMMENT '手续费总额',
    `total_fee_return_amount` decimal(18,2) NOT NULL DEFAULT 0.00 COMMENT '退费总额',
    `net_amount` decimal(18,2) NOT NULL DEFAULT 0.00 COMMENT '净变动额',
    `available_at` datetime(3) NOT NULL COMMENT '正式出账可用时间',
    `generated_at` datetime(3) NOT NULL COMMENT '生成时间',
    `file_location` varchar(255) DEFAULT NULL COMMENT '持久化存储路径',
    `file_content` longtext COMMENT '账单文件原始内容(可直接存储或用于离线解析)',
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_scope_prof_merch_acc_type_date_ver` (`scope_id`, `profile_id`, `channel_merchant_no`, `account_id`, `statement_type`, `business_date`, `version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Mock渠道日账单元数据表';

-- 10. 对账任务表 t_recon_task
CREATE TABLE IF NOT EXISTS `t_recon_task` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `task_no` varchar(64) NOT NULL COMMENT '任务唯一编号',
    `scope_id` varchar(32) NOT NULL DEFAULT 'DEFAULT' COMMENT '作用域',
    `profile_id` varchar(64) NOT NULL COMMENT '协议ID',
    `channel_merchant_no` varchar(64) NOT NULL COMMENT '渠道商户号',
    `account_id` varchar(64) NOT NULL COMMENT '资金账户',
    `statement_type` varchar(32) NOT NULL COMMENT '账单类型 TRADE/FUND/SETTLEMENT',
    `business_date` date NOT NULL COMMENT '清算业务日期',
    `mode` varchar(16) NOT NULL DEFAULT 'DAILY' COMMENT '核对模式 INTRADAY/DAILY',
    `window_end` datetime(3) NOT NULL COMMENT '核对窗口截止点',
    `task_status` varchar(24) NOT NULL DEFAULT 'INIT' COMMENT '状态: INIT/RUNNING/COMPLETED/FAILED',
    `lease_token` varchar(64) DEFAULT NULL COMMENT '租约Token',
    `locked_until` datetime(3) DEFAULT NULL COMMENT '锁定到期时间',
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    `updated_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_task_no` (`task_no`),
    UNIQUE KEY `uk_task_scope_range` (`scope_id`, `profile_id`, `channel_merchant_no`, `account_id`, `statement_type`, `business_date`, `mode`, `window_end`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='对账逻辑任务表';

-- 11. 对账单次执行记录表 t_recon_run
CREATE TABLE IF NOT EXISTS `t_recon_run` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `run_no` varchar(64) NOT NULL COMMENT '执行批次编号',
    `task_no` varchar(64) NOT NULL COMMENT '所属逻辑任务号',
    `run_seq` int NOT NULL DEFAULT 1 COMMENT '重试/重跑序号',
    `file_id` bigint DEFAULT NULL COMMENT '输入账单文件ID',
    `file_hash` varchar(64) DEFAULT NULL COMMENT '账单Hash',
    `snapshot_cutoff` datetime(3) NOT NULL COMMENT '平台数据快照截止时间',
    `rule_version` int NOT NULL DEFAULT 1 COMMENT '核对规则版本',
    `channel_count` int NOT NULL DEFAULT 0 COMMENT '渠道条目数',
    `local_count` int NOT NULL DEFAULT 0 COMMENT '平台条目数',
    `matched_count` int NOT NULL DEFAULT 0 COMMENT '匹配成功条数',
    `diff_count` int NOT NULL DEFAULT 0 COMMENT '产生差异条数',
    `channel_amount` decimal(18,2) NOT NULL DEFAULT 0.00 COMMENT '渠道发生额汇总',
    `local_amount` decimal(18,2) NOT NULL DEFAULT 0.00 COMMENT '平台发生额汇总',
    `matched_amount` decimal(18,2) NOT NULL DEFAULT 0.00 COMMENT '匹配金额汇总',
    `diff_amount` decimal(18,2) NOT NULL DEFAULT 0.00 COMMENT '差异金额汇总',
    `execution_status` varchar(24) NOT NULL DEFAULT 'RUNNING' COMMENT '状态: RUNNING/COMPLETED/FAILED',
    `outcome` varchar(24) NOT NULL DEFAULT 'PENDING' COMMENT '结果: BALANCED(平账)/DIFF_FOUND(有差异)/ERROR',
    `started_at` datetime(3) NOT NULL COMMENT '启动时间',
    `completed_at` datetime(3) DEFAULT NULL COMMENT '完成时间',
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_run_no` (`run_no`),
    UNIQUE KEY `uk_task_run_seq` (`task_no`, `run_seq`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='对账执行记录表';

-- 12. 导入的原始账单文件元数据表 t_recon_file
CREATE TABLE IF NOT EXISTS `t_recon_file` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `file_no` varchar(64) NOT NULL COMMENT '文件业务编号',
    `profile_id` varchar(64) NOT NULL COMMENT '协议ID',
    `channel_merchant_no` varchar(64) NOT NULL COMMENT '商户号',
    `statement_type` varchar(32) NOT NULL COMMENT '账单类型',
    `business_date` date NOT NULL COMMENT '业务日期',
    `version` int NOT NULL DEFAULT 1 COMMENT '文件版本',
    `checksum` varchar(64) NOT NULL COMMENT '校验和',
    `parser_version` varchar(32) NOT NULL DEFAULT 'v1' COMMENT '解析器版本',
    `file_location` varchar(255) DEFAULT NULL COMMENT '文件存储位置',
    `raw_content` longtext COMMENT '原始账单文本',
    `row_count` int NOT NULL DEFAULT 0 COMMENT '明细行数',
    `status` varchar(24) NOT NULL DEFAULT 'LOADED' COMMENT '状态 LOADED/PARSED/FAILED',
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_file_no` (`file_no`),
    UNIQUE KEY `uk_file_dedup` (`profile_id`, `channel_merchant_no`, `statement_type`, `business_date`, `version`, `checksum`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='对账原始文件元数据表';

-- 13. 标准化渠道明细条目 t_recon_channel_item
CREATE TABLE IF NOT EXISTS `t_recon_channel_item` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `file_id` bigint NOT NULL COMMENT '所属文件ID',
    `line_no` int NOT NULL COMMENT '文件中原始行号',
    `item_type` varchar(32) NOT NULL COMMENT '条目类型 PAYMENT/REFUND/FEE/POSTING',
    `out_trade_no` varchar(64) DEFAULT NULL COMMENT '商户单号(attempt_no)',
    `channel_trade_no` varchar(64) DEFAULT NULL COMMENT '渠道流水号',
    `refund_no` varchar(64) DEFAULT NULL COMMENT '退款单号',
    `amount` decimal(18,2) NOT NULL COMMENT '发生金额',
    `fee_amount` decimal(18,2) NOT NULL DEFAULT 0.00 COMMENT '手续费',
    `currency` varchar(8) NOT NULL DEFAULT 'CNY' COMMENT '币种',
    `status` varchar(32) NOT NULL COMMENT '状态',
    `channel_time` datetime(3) NOT NULL COMMENT '渠道时间',
    `raw_line` text COMMENT '原始文本行内容',
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_file_line` (`file_id`, `line_no`),
    KEY `idx_out_trade_no` (`out_trade_no`),
    KEY `idx_channel_trade_no` (`channel_trade_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='标准化渠道对账明细';

-- 14. 平台本次运行提取的快照明细 t_recon_local_item
CREATE TABLE IF NOT EXISTS `t_recon_local_item` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `run_no` varchar(64) NOT NULL COMMENT '执行批次号',
    `financial_event_no` varchar(64) NOT NULL COMMENT '平台金融事件号',
    `item_type` varchar(32) NOT NULL COMMENT '类型 PAYMENT/REFUND/FEE',
    `biz_no` varchar(64) NOT NULL COMMENT '业务编号(attempt_no/refund_no)',
    `order_no` varchar(64) DEFAULT NULL COMMENT '订单号',
    `amount` decimal(18,2) NOT NULL COMMENT '金额',
    `fee_amount` decimal(18,2) NOT NULL DEFAULT 0.00 COMMENT '手续费',
    `currency` varchar(8) NOT NULL DEFAULT 'CNY' COMMENT '币种',
    `status` varchar(32) NOT NULL COMMENT '状态',
    `occurred_at` datetime(3) NOT NULL COMMENT '发生时间',
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_run_event` (`run_no`, `financial_event_no`),
    KEY `idx_biz_no` (`biz_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='平台对账快照明细';

-- 15. 双边比对匹配记录表 t_recon_match
CREATE TABLE IF NOT EXISTS `t_recon_match` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `run_no` varchar(64) NOT NULL COMMENT '执行批次号',
    `match_type` varchar(32) NOT NULL COMMENT '核对类型 L1_TRADE/L1_REFUND/L2_FEE/L3_SETTLEMENT',
    `channel_item_id` bigint DEFAULT NULL COMMENT '渠道条目ID',
    `local_item_id` bigint DEFAULT NULL COMMENT '平台条目ID',
    `biz_key` varchar(64) NOT NULL COMMENT '匹配主键(如 attempt_no/refund_no)',
    `channel_amount` decimal(18,2) DEFAULT NULL COMMENT '渠道金额',
    `local_amount` decimal(18,2) DEFAULT NULL COMMENT '平台金额',
    `match_status` varchar(24) NOT NULL COMMENT '匹配状态: MATCHED/AMOUNT_DIFF/STATUS_DIFF/LEFT_ONLY/RIGHT_ONLY',
    `rule_version` int NOT NULL DEFAULT 1 COMMENT '核对规则版本',
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_run_match` (`run_no`, `biz_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='双边比对记录表';

-- 16. 稳定差异工单表 t_recon_diff
CREATE TABLE IF NOT EXISTS `t_recon_diff` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `diff_no` varchar(64) NOT NULL COMMENT '差异工单号',
    `diff_key` varchar(128) NOT NULL COMMENT '稳定差异唯一Key',
    `profile_id` varchar(64) NOT NULL COMMENT '协议ID',
    `channel_merchant_no` varchar(64) NOT NULL COMMENT '商户号',
    `account_id` varchar(64) NOT NULL COMMENT '账户ID',
    `recon_layer` varchar(16) NOT NULL COMMENT '对账层级: L1/L2/L3/L4',
    `diff_type` varchar(32) NOT NULL COMMENT '差异类型: CHANNEL_ONLY_PAYMENT/PLATFORM_ONLY_PAYMENT/AMOUNT_MISMATCH/DUPLICATE_CHANNEL_ENTRY/EXCESS_PAYMENT/LATE_PAYMENT/REFUND_STAGE_MISMATCH/FEE_MISMATCH/SETTLEMENT_DELAYED/BILL_MISSING',
    `severity` varchar(16) NOT NULL DEFAULT 'HIGH' COMMENT '严重级别: LOW/MEDIUM/HIGH/CRITICAL',
    `biz_key` varchar(64) NOT NULL COMMENT '业务标识(attemptNo/refundNo/settlementNo)',
    `order_no` varchar(64) DEFAULT NULL COMMENT '关联订单号',
    `attempt_no` varchar(64) DEFAULT NULL COMMENT '关联尝试号',
    `refund_no` varchar(64) DEFAULT NULL COMMENT '关联退款单号',
    `channel_amount` decimal(18,2) DEFAULT NULL COMMENT '渠道金额',
    `local_amount` decimal(18,2) DEFAULT NULL COMMENT '平台金额',
    `channel_status` varchar(32) DEFAULT NULL COMMENT '渠道状态',
    `local_status` varchar(32) DEFAULT NULL COMMENT '平台状态',
    `first_seen_run_no` varchar(64) NOT NULL COMMENT '首次检出批次',
    `last_seen_run_no` varchar(64) NOT NULL COMMENT '最新检出批次',
    `status` varchar(24) NOT NULL DEFAULT 'OPEN' COMMENT '状态: OPEN/INVESTIGATING/REPAIRING/WAIT_RECHECK/RESOLVED/WAIVED/REOPENED',
    `waive_reason` varchar(255) DEFAULT NULL COMMENT '豁免原因',
    `first_seen_at` datetime(3) NOT NULL COMMENT '首次发现时间',
    `last_seen_at` datetime(3) NOT NULL COMMENT '最新确认时间',
    `resolved_at` datetime(3) DEFAULT NULL COMMENT '解决时间',
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    `updated_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_diff_no` (`diff_no`),
    UNIQUE KEY `uk_diff_key` (`diff_key`),
    KEY `idx_status_layer` (`status`, `recon_layer`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='对账差异工单表';

-- 17. 差异处理与状态历史追溯表 t_recon_diff_history
CREATE TABLE IF NOT EXISTS `t_recon_diff_history` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `diff_no` varchar(64) NOT NULL COMMENT '差异工单号',
    `action` varchar(32) NOT NULL COMMENT '动作: DETECTED/CLAIM/REPAIR_COMMAND/RECHECK_PASS/REOPEN/WAIVE/RESOLVE',
    `from_status` varchar(24) DEFAULT NULL COMMENT '原状态',
    `to_status` varchar(24) NOT NULL COMMENT '新状态',
    `operator` varchar(64) NOT NULL COMMENT '操作人(SYSTEM/用户ID)',
    `evidence` text COMMENT '处理依据/快照JSON',
    `command_no` varchar(64) DEFAULT NULL COMMENT '触发的修复命令号',
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_diff_no` (`diff_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='差异工单处理历史';

-- 18. 差异修复幂等命令记录表 t_recon_repair_command
CREATE TABLE IF NOT EXISTS `t_recon_repair_command` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `command_no` varchar(64) NOT NULL COMMENT '命令编号',
    `diff_no` varchar(64) NOT NULL COMMENT '差异工单号',
    `command_type` varchar(32) NOT NULL COMMENT '命令类型: CHANNEL_QUERY/SUSPENSE_CREDIT/SUSPENSE_REFUND/REORDER_CONFIRM/RECHECK_TASK',
    `request_key` varchar(128) NOT NULL COMMENT '防重复幂等键',
    `payload_json` text COMMENT '下发命令参数JSON',
    `status` varchar(24) NOT NULL DEFAULT 'PENDING' COMMENT '状态: PENDING/EXECUTED/FAILED',
    `result_json` text COMMENT '执行结果快照JSON',
    `operator` varchar(64) NOT NULL COMMENT '操作人',
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    `updated_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_repair_cmd_no` (`command_no`),
    UNIQUE KEY `uk_diff_req_key` (`diff_no`, `request_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='对账差异修复命令表';
