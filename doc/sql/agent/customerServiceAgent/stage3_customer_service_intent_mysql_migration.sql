-- 智能客服 L2 意图 RAG —— MySQL 增量建表及初始数据
-- 设计：doc/agent/customerServiceAgent/implementation_design.md 第 7.1、10.8、12 节
-- 目标：MySQL 8.0 db_agent；先初始化 doc/sql/rag/stage3_rag_schema.sql。
-- 后续：执行同目录 stage3_customer_service_intent_pgvector_migration.sql，再运行索引任务。
-- 仅新增意图库，不修改知识库、Agent 基础表或业务表；不生成虚假向量。
-- 可重复执行；CREATE IF NOT EXISTS 不修复已存在但结构不同的同名表。
-- 种子仅插入缺失业务键，不覆盖管理员修改；DDL 在 MySQL 中隐式提交。
-- 应用层负责状态枚举、阈值 [0,1]、版本正整数及路由白名单校验。

USE `db_agent`;
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS `t_customer_service_intent` (
    `intent_code` varchar(48) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    `intent_name` varchar(64) NOT NULL,
    `description` varchar(512) NOT NULL COMMENT '语义边界，供 L3 枚举说明和维护使用',
    `route_node` varchar(48) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '须通过服务端固定白名单校验',
    `required_slots_json` json NOT NULL COMMENT '所需槽位名数组；提取后仍校验归属，缺失则澄清',
    `similarity_threshold` decimal(5,4) DEFAULT NULL COMMENT 'NULL 使用全局 0.88；仅 L2 使用，严格大于',
    `intent_version` int NOT NULL DEFAULT 1 COMMENT '语义或路由变化时递增并重建关联示例',
    `status` varchar(16) NOT NULL DEFAULT 'DISABLED' COMMENT 'ENABLED, DISABLED',
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`intent_code`),
    KEY `idx_cs_intent_status` (`status`, `route_node`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客服意图定义，MySQL 为权威源';

CREATE TABLE IF NOT EXISTS `t_customer_service_intent_example` (
    `example_no` varchar(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    `example_version` int NOT NULL DEFAULT 1,
    `intent_code` varchar(48) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    `intent_version` int NOT NULL DEFAULT 1 COMMENT '必须等于当前意图定义版本',
    `example_text` varchar(512) NOT NULL COMMENT '审核后的示例问句，禁止存用户隐私',
    `normalized_text` varchar(512) NOT NULL COMMENT 'NFKC、小写、首尾去空白及连续空白合并',
    `example_hash` char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'SHA256(normalized_text)，UTF-8 小写 hex',
    `status` varchar(16) NOT NULL DEFAULT 'ENABLED' COMMENT 'ENABLED, DISABLED',
    `embedding_status` varchar(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING, PROCESSING, READY, FAILED',
    `embedding_model` varchar(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
    `embedding_dim` int DEFAULT NULL,
    `embedding_input_hash` char(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT 'SHA256(model + | + normalized_text)，与 PG 一致',
    `last_error` varchar(512) DEFAULT NULL COMMENT '脱敏索引错误摘要',
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`example_no`, `example_version`),
    KEY `idx_cs_example_intent` (`intent_code`, `intent_version`, `status`),
    KEY `idx_cs_example_embed` (`embedding_status`, `status`),
    KEY `idx_cs_example_hash` (`intent_code`, `example_hash`),
    CONSTRAINT `fk_cs_example_intent` FOREIGN KEY (`intent_code`)
        REFERENCES `t_customer_service_intent` (`intent_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客服意图示例，真实向量写入 PG 后才标记 READY';

-- 初始数据在下方同一事务内导入。使用主键冲突时自赋值，不覆盖已有内容。
START TRANSACTION;

-- L1 / OTHER 只注册编码，不生成 L2 示例；P1 意图默认 DISABLED。
INSERT INTO `t_customer_service_intent`
    (`intent_code`, `intent_name`, `description`, `route_node`, `required_slots_json`, `status`)
VALUES
    ('GREETING', '问候', '完整简单问候，使用固定话术', 'TEMPLATE_RESPONSE', JSON_ARRAY(), 'ENABLED'),
    ('BOT_IDENTITY', '身份询问', '询问智能客服身份，使用固定话术', 'TEMPLATE_RESPONSE', JSON_ARRAY(), 'ENABLED'),
    ('ORDER_SEARCH', '订单查询', '查询自己的订单列表或选择订单', 'ORDER_AGENT', JSON_ARRAY(), 'ENABLED'),
    ('ORDER_STATUS', '订单状态', '具体订单的支付、托管或交付状态，不解释一般结算规则', 'ORDER_AGENT', JSON_ARRAY('orderNo'), 'ENABLED'),
    ('ORDER_TIMELINE', '订单时间线', '查询具体订单的状态流转与关键时间', 'ORDER_AGENT', JSON_ARRAY('orderNo'), 'ENABLED'),
    ('SETTLEMENT', '订单结算', '查询自己具体订单的结算进度，区别于通用结算规则', 'ORDER_AGENT', JSON_ARRAY('orderNo'), 'ENABLED'),
    ('AFTER_SALE_PROGRESS', '售后进度', '查询已提交售后单的处理进度', 'AFTER_SALE_AGENT', JSON_ARRAY('disputeNo'), 'ENABLED'),
    ('AFTER_SALE_GUIDE', '售后指引', '咨询申请售后的条件、证据或流程，不直接执行退款', 'AFTER_SALE_AGENT', JSON_ARRAY(), 'ENABLED'),
    ('AFTER_SALE_STATUS_COMBINED', '订单售后联合查询', '同时查询有关联的具体订单与售后单状态', 'AFTER_SALE_AGENT', JSON_ARRAY('orderNo', 'disputeNo'), 'ENABLED'),
    ('PLATFORM_RULE', '平台规则', '禁售、担保交易等公开规则', 'RULE_AGENT', JSON_ARRAY(), 'ENABLED'),
    ('SETTLEMENT_RULE', '结算规则', '通用手续费及结算周期，不是具体订单结算状态', 'RULE_AGENT', JSON_ARRAY(), 'ENABLED'),
    ('WITHDRAW_RULE', '提现规则', '通用提现条件和到账时效，不查询具体提现流水', 'RULE_AGENT', JSON_ARRAY(), 'ENABLED'),
    ('ACCOUNT_RULE', '账号规则', '通用实名、绑定、安全规则，实际盗号或损失应升级人工', 'RULE_AGENT', JSON_ARRAY(), 'ENABLED'),
    ('COMPLAINT', '投诉', '对平台或商家提出投诉；高风险由升级规则优先处理', 'COMPLAINT_AGENT', JSON_ARRAY(), 'ENABLED'),
    ('HUMAN_HELP', '转人工', '用户明确要求人工客服', 'COMPLAINT_AGENT', JSON_ARRAY(), 'ENABLED'),
    ('PRE_SALE_PRODUCT', '售前商品咨询', '公开商品说明、交付方式与适用范围，P1', 'PRE_SALE_AGENT', JSON_ARRAY('itemId'), 'DISABLED'),
    ('PRE_SALE_PROCESS', '售前流程咨询', '通用下单和支付操作，P1', 'PRE_SALE_AGENT', JSON_ARRAY(), 'DISABLED'),
    ('PRE_SALE_LIMIT', '售前购买限制', '商品限购、库存或交易限制，P1', 'PRE_SALE_AGENT', JSON_ARRAY('itemId'), 'DISABLED'),
    ('OTHER', '其他问题', '无法归入已支持意图时使用兜底，不向量化', 'FALLBACK', JSON_ARRAY(), 'ENABLED')
ON DUPLICATE KEY UPDATE `intent_code` = `intent_code`;

-- 初始中文样例已符合归一化规则，未调用 Embedding，状态保持 PENDING。
-- 后续补充真实同义问法和易混淆负例后评估，不把这些种子视为已验证分类器。
INSERT INTO `t_customer_service_intent_example`
    (`example_no`, `example_version`, `intent_code`, `intent_version`,
     `example_text`, `normalized_text`, `example_hash`)
VALUES
    ('CS-ORDER-SEARCH-001', 1, 'ORDER_SEARCH', 1, '帮我看看最近买了哪些订单', '帮我看看最近买了哪些订单', SHA2('帮我看看最近买了哪些订单', 256)),
    ('CS-ORDER-SEARCH-002', 1, 'ORDER_SEARCH', 1, '我想查一下我的订单列表', '我想查一下我的订单列表', SHA2('我想查一下我的订单列表', 256)),
    ('CS-ORDER-STATUS-001', 1, 'ORDER_STATUS', 1, '我的订单到哪一步了？', '我的订单到哪一步了?', SHA2('我的订单到哪一步了?', 256)),
    ('CS-ORDER-STATUS-002', 1, 'ORDER_STATUS', 1, '我买的账号什么时候交付', '我买的账号什么时候交付', SHA2('我买的账号什么时候交付', 256)),
    ('CS-ORDER-TIMELINE-001', 1, 'ORDER_TIMELINE', 1, '我想看订单从下单到交付的时间记录', '我想看订单从下单到交付的时间记录', SHA2('我想看订单从下单到交付的时间记录', 256)),
    ('CS-ORDER-TIMELINE-002', 1, 'ORDER_TIMELINE', 1, '这笔订单是什么时候付款的', '这笔订单是什么时候付款的', SHA2('这笔订单是什么时候付款的', 256)),
    ('CS-SETTLEMENT-001', 1, 'SETTLEMENT', 1, '我的这笔订单什么时候能结算', '我的这笔订单什么时候能结算', SHA2('我的这笔订单什么时候能结算', 256)),
    ('CS-SETTLEMENT-002', 1, 'SETTLEMENT', 1, '这单已经完成了为什么还没给我结算', '这单已经完成了为什么还没给我结算', SHA2('这单已经完成了为什么还没给我结算', 256)),
    ('CS-AFTER-PROGRESS-001', 1, 'AFTER_SALE_PROGRESS', 1, '我提交的售后申请处理到哪一步了', '我提交的售后申请处理到哪一步了', SHA2('我提交的售后申请处理到哪一步了', 256)),
    ('CS-AFTER-PROGRESS-002', 1, 'AFTER_SALE_PROGRESS', 1, '我的退款申请现在是什么状态', '我的退款申请现在是什么状态', SHA2('我的退款申请现在是什么状态', 256)),
    ('CS-AFTER-GUIDE-001', 1, 'AFTER_SALE_GUIDE', 1, '卡密无效怎么申请售后', '卡密无效怎么申请售后', SHA2('卡密无效怎么申请售后', 256)),
    ('CS-AFTER-GUIDE-002', 1, 'AFTER_SALE_GUIDE', 1, '我要申请退货应该怎么操作', '我要申请退货应该怎么操作', SHA2('我要申请退货应该怎么操作', 256)),
    ('CS-AFTER-COMBINED-001', 1, 'AFTER_SALE_STATUS_COMBINED', 1, '帮我一起查一下这笔订单和对应售后单的状态', '帮我一起查一下这笔订单和对应售后单的状态', SHA2('帮我一起查一下这笔订单和对应售后单的状态', 256)),
    ('CS-AFTER-COMBINED-002', 1, 'AFTER_SALE_STATUS_COMBINED', 1, '这个订单现在什么状态，相关退款又处理到哪了', '这个订单现在什么状态,相关退款又处理到哪了', SHA2('这个订单现在什么状态,相关退款又处理到哪了', 256)),
    ('CS-PLATFORM-RULE-001', 1, 'PLATFORM_RULE', 1, '平台禁止出售哪些商品', '平台禁止出售哪些商品', SHA2('平台禁止出售哪些商品', 256)),
    ('CS-PLATFORM-RULE-002', 1, 'PLATFORM_RULE', 1, '平台担保交易有什么规则', '平台担保交易有什么规则', SHA2('平台担保交易有什么规则', 256)),
    ('CS-SETTLEMENT-RULE-001', 1, 'SETTLEMENT_RULE', 1, '平台的交易手续费怎么算', '平台的交易手续费怎么算', SHA2('平台的交易手续费怎么算', 256)),
    ('CS-SETTLEMENT-RULE-002', 1, 'SETTLEMENT_RULE', 1, '平台规定的结算周期是多久', '平台规定的结算周期是多久', SHA2('平台规定的结算周期是多久', 256)),
    ('CS-WITHDRAW-RULE-001', 1, 'WITHDRAW_RULE', 1, '平台提现一般多久到账', '平台提现一般多久到账', SHA2('平台提现一般多久到账', 256)),
    ('CS-WITHDRAW-RULE-002', 1, 'WITHDRAW_RULE', 1, '提现需要满足哪些条件', '提现需要满足哪些条件', SHA2('提现需要满足哪些条件', 256)),
    ('CS-ACCOUNT-RULE-001', 1, 'ACCOUNT_RULE', 1, '平台实名认证需要哪些资料', '平台实名认证需要哪些资料', SHA2('平台实名认证需要哪些资料', 256)),
    ('CS-ACCOUNT-RULE-002', 1, 'ACCOUNT_RULE', 1, '账号绑定有什么要求', '账号绑定有什么要求', SHA2('账号绑定有什么要求', 256)),
    ('CS-COMPLAINT-001', 1, 'COMPLAINT', 1, '我要投诉这个卖家', '我要投诉这个卖家', SHA2('我要投诉这个卖家', 256)),
    ('CS-COMPLAINT-002', 1, 'COMPLAINT', 1, '你们处理太慢了我要投诉平台', '你们处理太慢了我要投诉平台', SHA2('你们处理太慢了我要投诉平台', 256)),
    ('CS-HUMAN-HELP-001', 1, 'HUMAN_HELP', 1, '请给我转人工客服', '请给我转人工客服', SHA2('请给我转人工客服', 256)),
    ('CS-HUMAN-HELP-002', 1, 'HUMAN_HELP', 1, '我要找真人客服处理', '我要找真人客服处理', SHA2('我要找真人客服处理', 256)),
    ('CS-PRE-PRODUCT-001', 1, 'PRE_SALE_PRODUCT', 1, '这件商品支持哪些使用场景', '这件商品支持哪些使用场景', SHA2('这件商品支持哪些使用场景', 256)),
    ('CS-PRE-PRODUCT-002', 1, 'PRE_SALE_PRODUCT', 1, '这个商品购买后以什么方式交付', '这个商品购买后以什么方式交付', SHA2('这个商品购买后以什么方式交付', 256)),
    ('CS-PRE-PROCESS-001', 1, 'PRE_SALE_PROCESS', 1, '第一次买东西该怎么下单付款', '第一次买东西该怎么下单付款', SHA2('第一次买东西该怎么下单付款', 256)),
    ('CS-PRE-PROCESS-002', 1, 'PRE_SALE_PROCESS', 1, '购买商品的具体步骤是什么', '购买商品的具体步骤是什么', SHA2('购买商品的具体步骤是什么', 256)),
    ('CS-PRE-LIMIT-001', 1, 'PRE_SALE_LIMIT', 1, '这个商品每个人最多能买几件', '这个商品每个人最多能买几件', SHA2('这个商品每个人最多能买几件', 256)),
    ('CS-PRE-LIMIT-002', 1, 'PRE_SALE_LIMIT', 1, '这件商品有什么购买限制', '这件商品有什么购买限制', SHA2('这件商品有什么购买限制', 256))
ON DUPLICATE KEY UPDATE `example_no` = `example_no`;

COMMIT;

-- 执行后校验（全新库预期 19 个定义、32 个示例、全部 PENDING；P1 的 3 个定义禁用）：
-- SELECT status, COUNT(*) FROM t_customer_service_intent GROUP BY status;
-- SELECT embedding_status, COUNT(*) FROM t_customer_service_intent_example GROUP BY embedding_status;
-- SELECT e.example_no FROM t_customer_service_intent_example e
--  LEFT JOIN t_customer_service_intent i ON e.intent_code = i.intent_code
--  WHERE i.intent_code IS NULL; -- 预期 0 行
-- SELECT example_no FROM t_customer_service_intent_example
--  WHERE example_hash <> SHA2(normalized_text, 256); -- 预期 0 行
-- 有效发布集合查询，模型与维度由服务端当前 EmbeddingClient 参数绑定：
-- SELECT e.example_no, e.example_version, e.intent_code, e.intent_version, e.embedding_input_hash
--   FROM t_customer_service_intent_example e
--   JOIN t_customer_service_intent i ON e.intent_code = i.intent_code
--    AND e.intent_version = i.intent_version
--  WHERE i.status = 'ENABLED' AND e.status = 'ENABLED' AND e.embedding_status = 'READY'
--    AND e.embedding_model = ? AND e.embedding_dim = ?;
-- READY 前必须写齐 model / dim / input_hash；没有有效发布集合时直接使用 L3。
-- 更新问句必须新增版本并禁用旧版本；更换模型后重建并重新校准阈值。
