-- ==============================================================================
-- v2_payment_refund_and_fund_suspense.sql
-- 支付多渠道演进：原路退款单、Mock渠道退款流水、Mock账户明细记账与资金挂账账户
-- 适用数据库：db_order 与 db_user
-- ==============================================================================

USE `db_order`;

-- 1. 创建平台侧支付退款单 t_payment_refund_order
CREATE TABLE IF NOT EXISTS `t_payment_refund_order` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `refund_no` varchar(64) NOT NULL COMMENT '退款全局唯一编号',
    `original_attempt_no` varchar(64) NOT NULL COMMENT '原支付成功的尝试号',
    `order_no` varchar(64) NOT NULL COMMENT '所属订单号',
    `amount` decimal(18,2) NOT NULL COMMENT '本次退款金额',
    `currency` varchar(8) NOT NULL DEFAULT 'CNY' COMMENT '币种',
    `reason` varchar(255) DEFAULT NULL COMMENT '退款原因',
    `status` varchar(32) NOT NULL DEFAULT 'REQUESTED' COMMENT '状态: REQUESTED/SUBMITTING/ACCEPTED/PROCESSING/SUCCEEDED/UNKNOWN/FAILED',
    `channel_refund_no` varchar(64) DEFAULT NULL COMMENT '外部渠道退款流水号',
    `fund_task_no` varchar(64) DEFAULT NULL COMMENT '关联内部资金任务号',
    `request_key` varchar(128) NOT NULL COMMENT '发起幂等键',
    `accepted_at` datetime(3) DEFAULT NULL COMMENT '渠道受理时间',
    `success_at` datetime(3) DEFAULT NULL COMMENT '渠道退款成功时间',
    `next_query_at` datetime(3) DEFAULT NULL COMMENT '下次补偿查单时间',
    `failure_code` varchar(64) DEFAULT NULL COMMENT '失败错误码',
    `failure_reason` varchar(255) DEFAULT NULL COMMENT '失败描述',
    `version` bigint NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    `updated_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_refund_no` (`refund_no`),
    UNIQUE KEY `uk_attempt_req_key` (`original_attempt_no`, `request_key`),
    KEY `idx_status_next_query` (`status`, `next_query_at`),
    KEY `idx_order_no` (`order_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='平台支付原路退款单';

-- 2. 创建 Mock 渠道独立退款单 t_mock_channel_refund
CREATE TABLE IF NOT EXISTS `t_mock_channel_refund` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `channel_merchant_no` varchar(64) NOT NULL COMMENT '商户号',
    `refund_no` varchar(64) NOT NULL COMMENT '商户退款单号(平台refund_no)',
    `channel_refund_no` varchar(64) NOT NULL COMMENT '模拟渠道退款流水号',
    `channel_trade_no` varchar(64) NOT NULL COMMENT '原支付模拟渠道流水号',
    `amount` decimal(18,2) NOT NULL COMMENT '退款金额',
    `fee_return_amount` decimal(18,2) NOT NULL DEFAULT 0.00 COMMENT '退回渠道手续费',
    `currency` varchar(8) NOT NULL DEFAULT 'CNY' COMMENT '币种',
    `status` varchar(24) NOT NULL DEFAULT 'SUCCEEDED' COMMENT '状态: ACCEPTED/PROCESSING/SUCCEEDED/FAILED',
    `accepted_at` datetime(3) NOT NULL COMMENT '受理时间',
    `success_at` datetime(3) DEFAULT NULL COMMENT '成功时间',
    `channel_business_date` date NOT NULL COMMENT '归属清算业务日期',
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    `updated_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_merchant_refund_no` (`channel_merchant_no`, `refund_no`),
    UNIQUE KEY `uk_channel_refund_no` (`channel_refund_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Mock渠道退款流水表';

-- 3. 创建 Mock 渠道账户明细与流水表 t_mock_channel_posting
CREATE TABLE IF NOT EXISTS `t_mock_channel_posting` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `account_id` varchar(64) NOT NULL COMMENT '渠道资金账户(如商户结算户/数币钱包)',
    `sequence_no` bigint NOT NULL COMMENT '账户内递增流水序号',
    `entry_type` varchar(32) NOT NULL COMMENT '类型: PAYMENT/REFUND/FEE/FEE_RETURN/SETTLEMENT_OUT/REDEEM',
    `source_event_no` varchar(64) NOT NULL COMMENT '动账来源业务流水号',
    `amount` decimal(18,2) NOT NULL COMMENT '变动金额',
    `direction` varchar(8) NOT NULL COMMENT '借贷方向: CREDIT(+)/DEBIT(-)',
    `balance_after` decimal(18,2) NOT NULL COMMENT '变动后账户余额',
    `channel_business_date` date NOT NULL COMMENT '业务日期',
    `occurred_at` datetime(3) NOT NULL COMMENT '发生时间',
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_account_sequence` (`account_id`, `sequence_no`),
    UNIQUE KEY `uk_source_event_entry` (`source_event_no`, `entry_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Mock渠道动账流水表';

-- 4. 扩展 db_user 的平台资金账户表，增加异常资金挂账字段
USE `db_user`;

ALTER TABLE `t_platform_account`
    ADD COLUMN `payment_suspense_amount` decimal(18,2) NOT NULL DEFAULT 0.00 COMMENT '异常资金挂账待处理余额(迟到/多付未用于订单)' AFTER `escrow_amount`;
