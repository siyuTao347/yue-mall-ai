-- ==============================================================================
-- v4_mock_demo_fixtures.sql
-- 支付多渠道演进：模拟日历基础数据与演示故障方案
-- 适用数据库：db_order
-- ==============================================================================

USE `db_order`;

-- 1. 初始化 MOCK_CN_ACQUIRER 2026年10月结算日历 (配合设计文档 4.2 示例)
-- 2026-10-17(周六)、2026-10-18(周日) 为非结算日，2026-10-19(周一) 为结算有效工作日
INSERT INTO `t_settlement_calendar_day` (`calendar_id`, `cal_date`, `is_settlement_day`, `version`, `reason`) VALUES
('MOCK_CN_ACQUIRER', '2026-10-12', 1, 1, '周一 正常结算工作日'),
('MOCK_CN_ACQUIRER', '2026-10-13', 1, 1, '周二 正常结算工作日'),
('MOCK_CN_ACQUIRER', '2026-10-14', 1, 1, '周三 正常结算工作日'),
('MOCK_CN_ACQUIRER', '2026-10-15', 1, 1, '周四 正常结算工作日'),
('MOCK_CN_ACQUIRER', '2026-10-16', 1, 1, '周五 正常结算工作日'),
('MOCK_CN_ACQUIRER', '2026-10-17', 0, 1, '周六 非结算日'),
('MOCK_CN_ACQUIRER', '2026-10-18', 0, 1, '周日 非结算日'),
('MOCK_CN_ACQUIRER', '2026-10-19', 1, 1, '周一 正常结算工作日'),
('MOCK_CN_ACQUIRER', '2026-10-20', 1, 1, '周二 正常结算工作日'),
('MOCK_CN_ACQUIRER', '2026-10-21', 1, 1, '周三 正常结算工作日'),
('MOCK_CN_ACQUIRER', '2026-10-22', 1, 1, '周四 正常结算工作日'),
('MOCK_CN_ACQUIRER', '2026-10-23', 1, 1, '周五 正常结算工作日'),
('MOCK_CN_ACQUIRER', '2026-10-24', 0, 1, '周六 非结算日'),
('MOCK_CN_ACQUIRER', '2026-10-25', 0, 1, '周日 非结算日'),
('MOCK_CN_ACQUIRER', '2026-10-26', 1, 1, '周一 正常结算工作日'),
('MOCK_CN_ACQUIRER', '2026-10-27', 1, 1, '周二 正常结算工作日'),
('MOCK_CN_ACQUIRER', '2026-10-28', 1, 1, '周三 正常结算工作日'),
('MOCK_CN_ACQUIRER', '2026-10-29', 1, 1, '周四 正常结算工作日'),
('MOCK_CN_ACQUIRER', '2026-10-30', 1, 1, '周五 正常结算工作日'),
('MOCK_CN_ACQUIRER', '2026-10-31', 0, 1, '周六 非结算日')
ON DUPLICATE KEY UPDATE `is_settlement_day` = VALUES(`is_settlement_day`), `reason` = VALUES(`reason`);

-- 2. 初始预置具名故障方案
INSERT INTO `t_mock_fault_plan` (`scope_id`, `scenario_key`, `plan_version`, `target_attempt_no`, `target_date`, `params_json`, `seed`, `enabled`) VALUES
('DEFAULT', 'DROP_PAYMENT_NOTIFY', 1, NULL, NULL, '{"action":"DROP_OUTBOX_SEND","description":"支付成功但模拟丢通知，验证主动查单补偿"}', 1001, 0),
('DEFAULT', 'AMOUNT_MISMATCH', 1, NULL, NULL, '{"action":"TAMPER_AMOUNT","delta":"-1.00","description":"模拟渠道扣款少1元，验证金额差异阻断与人工工单"}', 1002, 0)
ON DUPLICATE KEY UPDATE `params_json` = VALUES(`params_json`);
