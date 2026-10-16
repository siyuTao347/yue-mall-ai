-- ==============================================================================
-- v1_payment_attempt_and_mock_channel.sql
-- 支付多渠道演进：支付聚合单扩展、支付尝试、命令Outbox、事件Inbox与Mock渠道独立表
-- 适用数据库：db_order
-- ==============================================================================

USE `db_order`;

-- 1. 扩展 t_payment_order 表
ALTER TABLE `t_payment_order`
    ADD COLUMN `active_attempt_no` varchar(64) DEFAULT NULL COMMENT '当前处于活动中的尝试号' AFTER `payment_no`,
    ADD COLUMN `successful_attempt_no` varchar(64) DEFAULT NULL COMMENT '唯一已采用的成功尝试号' AFTER `active_attempt_no`,
    ADD COLUMN `currency` varchar(8) NOT NULL DEFAULT 'CNY' COMMENT '交易币种' AFTER `amount`,
    ADD COLUMN `payment_mode` varchar(16) NOT NULL DEFAULT 'MOCK' COMMENT '支付模式 MOCK/LIVE' AFTER `currency`,
    ADD COLUMN `data_origin` varchar(16) NOT NULL DEFAULT 'LEGACY_V1' COMMENT '数据来源 LEGACY_V1/NATIVE_V2' AFTER `payment_mode`,
    ADD COLUMN `version` bigint NOT NULL DEFAULT 0 COMMENT '乐观锁版本' AFTER `updated_time`,
    MODIFY COLUMN `channel` varchar(32) DEFAULT NULL COMMENT '展示用渠道代码',
    ADD KEY `idx_active_attempt` (`active_attempt_no`),
    ADD KEY `idx_successful_attempt` (`successful_attempt_no`);

-- 2. 创建支付尝试表 t_payment_attempt
CREATE TABLE IF NOT EXISTS `t_payment_attempt` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `attempt_no` varchar(64) NOT NULL COMMENT '支付尝试全局唯一编号',
    `payment_no` varchar(64) NOT NULL COMMENT '所属支付聚合单号',
    `order_no` varchar(64) NOT NULL COMMENT '所属订单号',
    `attempt_seq` int NOT NULL DEFAULT 1 COMMENT '同支付单下的尝试序号',
    `payment_method` varchar(32) NOT NULL COMMENT '用户选择的支付方式 UNIONPAY_BANK/WECHAT_PAY/ALIPAY/ECNY',
    `profile_id` varchar(64) NOT NULL COMMENT '渠道商户协议ID',
    `profile_version` int NOT NULL DEFAULT 1 COMMENT '协议版本',
    `profile_snapshot` text COMMENT '创建时渠道协议关键配置快照JSON',
    `channel_merchant_no` varchar(64) DEFAULT NULL COMMENT '模拟渠道商户号',
    `channel_trade_no` varchar(64) DEFAULT NULL COMMENT '外部渠道流水号',
    `out_trade_no` varchar(64) NOT NULL COMMENT '送往外部渠道的商户订单号(使用attempt_no)',
    `amount` decimal(18,2) NOT NULL COMMENT '尝试金额',
    `currency` varchar(8) NOT NULL DEFAULT 'CNY' COMMENT '币种',
    `status` varchar(32) NOT NULL DEFAULT 'CREATED' COMMENT '状态: CREATED/SUBMITTING/PENDING/SUCCEEDED/UNKNOWN/CLOSING/CLOSED/FAILED',
    `channel_success_at` datetime(3) DEFAULT NULL COMMENT '渠道成功发生时间',
    `channel_business_date` date DEFAULT NULL COMMENT '渠道业务归属日期',
    `local_confirmed_at` datetime(3) DEFAULT NULL COMMENT '平台确认事实时间',
    `request_key` varchar(128) NOT NULL COMMENT '客户端防重发起幂等键',
    `request_hash` varchar(64) DEFAULT NULL COMMENT '请求摘要哈希',
    `expires_at` datetime(3) DEFAULT NULL COMMENT '本次尝试有效截止时间',
    `next_query_at` datetime(3) DEFAULT NULL COMMENT '下次补偿轮询查单时间',
    `failure_code` varchar(64) DEFAULT NULL COMMENT '失败错误码',
    `allocation_status` varchar(32) NOT NULL DEFAULT 'ORDER_APPLIED' COMMENT '款项归属: ORDER_APPLIED/LATE_PAYMENT/EXCESS_PAYMENT/UNIDENTIFIED',
    `version` bigint NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    `updated_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_attempt_no` (`attempt_no`),
    UNIQUE KEY `uk_payment_seq` (`payment_no`, `attempt_seq`),
    UNIQUE KEY `uk_payment_request_key` (`payment_no`, `request_key`),
    UNIQUE KEY `uk_profile_merchant_channel_trade` (`profile_id`, `channel_merchant_no`, `channel_trade_no`),
    KEY `idx_status_next_query` (`status`, `next_query_at`),
    KEY `idx_profile_biz_date` (`profile_id`, `channel_business_date`, `id`),
    KEY `idx_order_no` (`order_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='支付尝试表';

-- 3. 创建支付命令可靠下发表 t_payment_command
CREATE TABLE IF NOT EXISTS `t_payment_command` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `command_no` varchar(64) NOT NULL COMMENT '命令唯一编号',
    `biz_key` varchar(64) NOT NULL COMMENT '业务对象编号(attempt_no/refund_no)',
    `command_type` varchar(32) NOT NULL COMMENT '命令类型 CREATE/CLOSE/REFUND/QUERY',
    `payload_hash` varchar(64) DEFAULT NULL COMMENT '载荷哈希',
    `payload_json` text COMMENT '命令载荷JSON',
    `status` varchar(24) NOT NULL DEFAULT 'PENDING' COMMENT '状态: PENDING/EXECUTING/SUCCESS/FAILED',
    `retry_count` int NOT NULL DEFAULT 0 COMMENT '重试次数',
    `lease_token` varchar(64) DEFAULT NULL COMMENT '执行租约Token',
    `locked_until` datetime(3) DEFAULT NULL COMMENT '锁定截止时间',
    `next_execute_at` datetime(3) DEFAULT NULL COMMENT '下次执行时间',
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    `updated_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_command_no` (`command_no`),
    UNIQUE KEY `uk_biz_type` (`biz_key`, `command_type`),
    KEY `idx_status_next_execute` (`status`, `next_execute_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='支付异步命令下发表';

-- 4. 创建支付通知持久化接收表 t_payment_event_inbox
CREATE TABLE IF NOT EXISTS `t_payment_event_inbox` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `event_id` varchar(64) NOT NULL COMMENT '渠道通知事件全局唯一ID',
    `profile_id` varchar(64) NOT NULL COMMENT '渠道商户协议ID',
    `channel_merchant_no` varchar(64) NOT NULL COMMENT '模拟商户号',
    `attempt_no` varchar(64) DEFAULT NULL COMMENT '关联尝试号',
    `refund_no` varchar(64) DEFAULT NULL COMMENT '关联退款单号',
    `channel_trade_no` varchar(64) DEFAULT NULL COMMENT '渠道流水号',
    `event_type` varchar(32) NOT NULL COMMENT '事件类型 PAYMENT/REFUND',
    `verified_payload` text NOT NULL COMMENT '验签通过的原始载荷JSON',
    `source` varchar(16) NOT NULL DEFAULT 'CALLBACK' COMMENT '事件来源: CALLBACK/QUERY',
    `processing_status` varchar(24) NOT NULL DEFAULT 'RECEIVED' COMMENT '处理状态: RECEIVED/PROCESSING/PROCESSED/FAILED',
    `failure_reason` varchar(255) DEFAULT NULL COMMENT '失败原因',
    `next_execute_at` datetime(3) DEFAULT NULL COMMENT '下次推进时间',
    `version` bigint NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    `updated_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_profile_merchant_event` (`profile_id`, `channel_merchant_no`, `event_id`),
    KEY `idx_proc_status_next` (`processing_status`, `next_execute_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='支付回调事实Inbox';

-- 5. 创建独立 Mock 渠道交易记录表 t_mock_channel_transaction
CREATE TABLE IF NOT EXISTS `t_mock_channel_transaction` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `profile_id` varchar(64) NOT NULL COMMENT '渠道协议ID',
    `channel_merchant_no` varchar(64) NOT NULL COMMENT '渠道商户号',
    `out_trade_no` varchar(64) NOT NULL COMMENT '外部商户订单号(即attempt_no)',
    `channel_trade_no` varchar(64) NOT NULL COMMENT '模拟渠道生成的流水号',
    `payment_method` varchar(32) NOT NULL COMMENT '支付方式 UNIONPAY_BANK/WECHAT_PAY/ALIPAY/ECNY',
    `amount` decimal(18,2) NOT NULL COMMENT '交易金额',
    `fee_amount` decimal(18,2) NOT NULL DEFAULT 0.00 COMMENT '渠道手续费',
    `currency` varchar(8) NOT NULL DEFAULT 'CNY' COMMENT '币种',
    `status` varchar(24) NOT NULL DEFAULT 'SUCCESS' COMMENT '状态 SUCCESS/FAIL/CANCELLED/CLOSED',
    `channel_success_at` datetime(3) NOT NULL COMMENT '渠道发生时间',
    `channel_business_date` date NOT NULL COMMENT '归属清算业务日期',
    `fault_plan_id` varchar(64) DEFAULT NULL COMMENT '生效的故障注入方案',
    `raw_details` text COMMENT '模拟扩展详情JSON',
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    `updated_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_profile_merchant_out_trade` (`profile_id`, `channel_merchant_no`, `out_trade_no`),
    UNIQUE KEY `uk_channel_trade_no` (`channel_trade_no`),
    KEY `idx_biz_date` (`channel_business_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Mock渠道独立交易记录表';

-- 6. 创建 Mock 渠道通知发件箱 t_mock_notification_outbox
CREATE TABLE IF NOT EXISTS `t_mock_notification_outbox` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `event_id` varchar(64) NOT NULL COMMENT '事件ID',
    `profile_id` varchar(64) NOT NULL COMMENT '协议ID',
    `channel_merchant_no` varchar(64) NOT NULL COMMENT '商户号',
    `event_type` varchar(32) NOT NULL COMMENT '事件类型 PAYMENT/REFUND',
    `payload_json` text NOT NULL COMMENT '通知报文JSON',
    `signature` varchar(256) NOT NULL COMMENT '签名值',
    `retry_count` int NOT NULL DEFAULT 0 COMMENT '重试投递次数',
    `next_send_at` datetime(3) NOT NULL COMMENT '下次投递时间',
    `status` varchar(24) NOT NULL DEFAULT 'PENDING' COMMENT '投递状态: PENDING/SENDING/SENT/FAILED',
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    `updated_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_outbox_event_id` (`event_id`),
    KEY `idx_status_next_send` (`status`, `next_send_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Mock渠道通知投递Outbox';

-- 7. 创建 Mock 故障注入计划表 t_mock_fault_plan
CREATE TABLE IF NOT EXISTS `t_mock_fault_plan` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `scope_id` varchar(32) NOT NULL DEFAULT 'DEFAULT' COMMENT '隔离作用域',
    `scenario_key` varchar(64) NOT NULL COMMENT '场景KEY(如 DROP_PAYMENT_NOTIFY/AMOUNT_MISMATCH)',
    `plan_version` int NOT NULL DEFAULT 1 COMMENT '版本',
    `target_attempt_no` varchar(64) DEFAULT NULL COMMENT '目标尝试号(空表示对日期范围生效)',
    `target_date` date DEFAULT NULL COMMENT '目标生效业务日期',
    `params_json` text COMMENT '注入参数JSON',
    `seed` bigint NOT NULL DEFAULT 0 COMMENT '随机种子',
    `enabled` tinyint NOT NULL DEFAULT 1 COMMENT '是否启用 1=启用 0=禁用',
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    `updated_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_scope_scenario_version` (`scope_id`, `scenario_key`, `plan_version`, `target_attempt_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Mock故障注入计划表';
