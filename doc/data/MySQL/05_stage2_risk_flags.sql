-- 阶段二演示数据 · 业务表风险标记回填（依赖 stage2_schema.sql 已执行）
-- 生成脚本：doc/data 演示数据集（阶段一 / 阶段二）
-- 依赖：db.sql -> doc/sql/stage1_schema.sql 已执行

SET NAMES utf8mb4;

USE `db_item`;

UPDATE `t_item` SET `risk_status` = 'WATCH', `risk_level` = 'MEDIUM', `risk_decision_no` = 'RISKDEC2026000013', `risk_reason` = '高价值账号，交易观察' WHERE `id` = 10;
UPDATE `t_item` SET `risk_status` = 'REJECTED', `risk_level` = 'CRITICAL', `risk_decision_no` = 'RISKDEC2026000012', `risk_reason` = '命中私下交易与异常低价' WHERE `id` = 15;
UPDATE `t_item` SET `risk_status` = 'MANUAL_REVIEW', `risk_level` = 'MEDIUM', `risk_decision_no` = 'RISKDEC2026000004', `risk_reason` = '新商家低价挂售待复核' WHERE `id` = 17;

USE `db_order`;

UPDATE `t_trade_order` SET `risk_status` = 'WATCH', `risk_level` = 'MEDIUM', `risk_decision_no` = 'RISKDEC2026000013', `risk_reason` = '新买家高额下单，标记观察' WHERE `order_no` = 'SO202609110005';
UPDATE `t_trade_order` SET `risk_status` = 'FROZEN', `risk_level` = 'HIGH', `risk_decision_no` = 'RISKDEC2026000007', `risk_reason` = '买卖双方强 IP 关联，冻结订单' WHERE `order_no` = 'SO202610040016';
UPDATE `t_trade_order` SET `risk_status` = 'MANUAL_REVIEW', `risk_level` = 'MEDIUM', `risk_decision_no` = 'RISKDEC2026000006', `risk_reason` = '新买家高额下单，禁止支付并转人工' WHERE `order_no` = 'SO202609250017';
UPDATE `t_trade_order` SET `risk_status` = 'REJECTED', `risk_level` = 'HIGH', `risk_decision_no` = 'RISKDEC2026000003', `risk_reason` = '买家 10 分钟下单 5 次，拒绝创建订单' WHERE `order_no` = 'SO202609260018';
UPDATE `t_trade_order` SET `risk_status` = 'MANUAL_REVIEW', `risk_level` = 'MEDIUM', `risk_decision_no` = 'RISKDEC2026000006', `risk_reason` = '新买家高额下单，禁止支付并转人工' WHERE `order_no` = 'SO202610030022';

USE `db_user`;

UPDATE `t_merchant` SET `risk_status` = 'NORMAL', `risk_level` = 'LOW', `risk_decision_no` = NULL, `risk_reason` = NULL WHERE `id` = 1;
UPDATE `t_merchant` SET `risk_status` = 'NORMAL', `risk_level` = 'LOW', `risk_decision_no` = NULL, `risk_reason` = NULL WHERE `id` = 2;
UPDATE `t_merchant` SET `risk_status` = 'NORMAL', `risk_level` = 'LOW', `risk_decision_no` = NULL, `risk_reason` = NULL WHERE `id` = 3;
UPDATE `t_merchant` SET `risk_status` = 'MANUAL_REVIEW', `risk_level` = 'MEDIUM', `risk_decision_no` = 'RISKDEC2026000009', `risk_reason` = '商家大额提现，转人工复核' WHERE `id` = 4;
UPDATE `t_merchant` SET `risk_status` = 'FROZEN', `risk_level` = 'HIGH', `risk_decision_no` = 'RISKDEC2026000010', `risk_reason` = '结算后快进快出且收款账户关联多商家，冻结提现' WHERE `id` = 5;
UPDATE `t_merchant` SET `risk_status` = 'REJECTED', `risk_level` = 'CRITICAL', `risk_decision_no` = 'RISKDEC2026000012', `risk_reason` = '命中私下交易敏感词且价格异常，拒绝上架' WHERE `id` = 6;
UPDATE `t_merchant` SET `risk_status` = 'MANUAL_REVIEW', `risk_level` = 'MEDIUM', `risk_decision_no` = 'RISKDEC2026000005', `risk_reason` = '商家申请描述命中账号找回风险词' WHERE `id` = 7;
UPDATE `t_merchant` SET `risk_status` = 'MANUAL_REVIEW', `risk_level` = 'MEDIUM', `risk_decision_no` = 'RISKDEC2026000011', `risk_reason` = '同一收款账户关联多个商家，转人工复核' WHERE `id` = 8;

-- 提现风险标记与延迟观察期
UPDATE `t_withdraw_request` SET `risk_status` = 'NORMAL', `risk_level` = 'LOW', `risk_decision_no` = NULL, `risk_reason` = NULL, `payout_delay_until` = NULL WHERE `withdraw_no` = 'WD202609200001';
UPDATE `t_withdraw_request` SET `risk_status` = 'NORMAL', `risk_level` = 'LOW', `risk_decision_no` = NULL, `risk_reason` = NULL, `payout_delay_until` = NULL WHERE `withdraw_no` = 'WD202609220002';
UPDATE `t_withdraw_request` SET `risk_status` = 'NORMAL', `risk_level` = 'LOW', `risk_decision_no` = NULL, `risk_reason` = NULL, `payout_delay_until` = NULL WHERE `withdraw_no` = 'WD202609250003';
UPDATE `t_withdraw_request` SET `risk_status` = 'NORMAL', `risk_level` = 'LOW', `risk_decision_no` = NULL, `risk_reason` = NULL, `payout_delay_until` = NULL WHERE `withdraw_no` = 'WD202610010004';
UPDATE `t_withdraw_request` SET `risk_status` = 'MANUAL_REVIEW', `risk_level` = 'MEDIUM', `risk_decision_no` = 'RISKDEC2026000009', `risk_reason` = '商家大额提现，转人工复核', `payout_delay_until` = '2026-10-09 10:00:00.000' WHERE `withdraw_no` = 'WD202610040005';
UPDATE `t_withdraw_request` SET `risk_status` = 'NORMAL', `risk_level` = 'LOW', `risk_decision_no` = NULL, `risk_reason` = NULL, `payout_delay_until` = NULL WHERE `withdraw_no` = 'WD202610060006';
UPDATE `t_withdraw_request` SET `risk_status` = 'FROZEN', `risk_level` = 'HIGH', `risk_decision_no` = 'RISKDEC2026000010', `risk_reason` = '结算后快进快出且收款账户关联多商家，冻结提现', `payout_delay_until` = NULL WHERE `withdraw_no` = 'WD202610070007';
UPDATE `t_withdraw_request` SET `risk_status` = 'MANUAL_REVIEW', `risk_level` = 'MEDIUM', `risk_decision_no` = 'RISKDEC2026000011', `risk_reason` = '同一收款账户关联多个商家，转人工复核', `payout_delay_until` = '2026-10-09 10:00:00.000' WHERE `withdraw_no` = 'WD202610070008';
