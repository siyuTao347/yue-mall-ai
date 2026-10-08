-- 阶段一演示数据 · db_item（类目 / 商品 / 审核 / 卡密 / 资产预留）
-- 生成脚本：doc/data 演示数据集（阶段一 / 阶段二）
-- 依赖：db.sql -> doc/sql/stage1_schema.sql 已执行

SET NAMES utf8mb4;

USE `db_item`;

-- 虚拟资产类目
INSERT INTO `t_item_category` (`id`, `category_name`, `parent_id`, `asset_type`, `delivery_mode`, `required_deposit`, `sort_order`, `enabled`, `created_time`, `updated_time`) VALUES
  (1, '虚拟饰品-武器箱', NULL, 'VIRTUAL_SKIN', 'MANUAL_DELIVERY', '100.00', 10, 1, '2026-09-01 00:00:00.000', '2026-09-01 00:00:00.000'),
  (2, '虚拟饰品-手套', NULL, 'VIRTUAL_SKIN', 'MANUAL_DELIVERY', '100.00', 20, 1, '2026-09-01 00:00:00.000', '2026-09-01 00:00:00.000'),
  (3, '虚拟饰品-匕首', NULL, 'VIRTUAL_SKIN', 'MANUAL_DELIVERY', '150.00', 30, 1, '2026-09-01 00:00:00.000', '2026-09-01 00:00:00.000'),
  (4, '虚拟饰品-枪械皮肤', NULL, 'VIRTUAL_SKIN', 'MANUAL_DELIVERY', '100.00', 40, 1, '2026-09-01 00:00:00.000', '2026-09-01 00:00:00.000'),
  (5, '卡密/激活码', NULL, 'CARD_SECRET', 'AUTO_CARD', '200.00', 50, 1, '2026-09-01 00:00:00.000', '2026-09-01 00:00:00.000'),
  (6, '游戏道具', NULL, 'GAME_ITEM', 'MANUAL_DELIVERY', '200.00', 60, 1, '2026-09-01 00:00:00.000', '2026-09-01 00:00:00.000'),
  (7, '游戏账号', NULL, 'GAME_ACCOUNT', 'MANUAL_DELIVERY', '1000.00', 70, 1, '2026-09-01 00:00:00.000', '2026-09-01 00:00:00.000'),
  (8, '代练/陪玩服务', NULL, 'SERVICE', 'MANUAL_DELIVERY', '500.00', 80, 1, '2026-09-01 00:00:00.000', '2026-09-01 00:00:00.000')
ON DUPLICATE KEY UPDATE `id` = `id`;

-- 商品（含阶段一 seller/audit 字段）
INSERT INTO `t_item` (`id`, `item_name`, `price`, `stock`, `frozen_stock`, `version`, `category_id`, `sub_title`, `image_url`, `detail_html`, `status`, `merchant_id`, `seller_id`, `asset_type`, `delivery_mode`, `source_description`, `risk_notice`, `audit_status`, `audit_remark`) VALUES
  (1, 'AK-47 | 红线', '1288.00', 5, 1, 0, 4, '略有磨损 | 磨损 0.21', 'https://images.unsplash.com/photo-1542751371-adc38448a05e?auto=format&fit=crop&w=800&q=80', '<p>AK-47 | 红线</p><p>略有磨损 | 磨损 0.21</p>', 1, 1, 7, 'VIRTUAL_SKIN', 'MANUAL_DELIVERY', '自用库存，附磨损截图', '虚拟资产存在交易风险，请确认后购买', 'APPROVED', NULL),
  (2, 'M4A1 消音版 | 二西莫夫', '356.00', 8, 1, 0, 4, '略有磨损 | 磨损 0.18', 'https://images.unsplash.com/photo-1511512578047-dfb367046420?auto=format&fit=crop&w=800&q=80', '<p>M4A1 消音版 | 二西莫夫</p><p>略有磨损 | 磨损 0.18</p>', 1, 1, 7, 'VIRTUAL_SKIN', 'MANUAL_DELIVERY', '自用库存', '虚拟资产存在交易风险，请确认后购买', 'APPROVED', NULL),
  (3, '折刀 | 渐变之色', '2680.00', 2, 0, 0, 3, '久经沙场 | 磨损 0.26', 'https://images.unsplash.com/photo-1550745165-9bc0b252726f?auto=format&fit=crop&w=800&q=80', '<p>折刀 | 渐变之色</p><p>久经沙场 | 磨损 0.26</p>', 1, 1, 7, 'VIRTUAL_SKIN', 'MANUAL_DELIVERY', '自用库存，支持视频验货', '虚拟资产存在交易风险，请确认后购买', 'APPROVED', NULL),
  (4, 'Steam 钱包充值卡密', '96.00', 500, 0, 0, 5, '100 元面值 | 官方渠道', 'https://images.unsplash.com/photo-1607083206968-13611e3d76db?auto=format&fit=crop&w=800&q=80', '<p>Steam 钱包充值卡密</p><p>100 元面值 | 官方渠道</p>', 1, 2, 8, 'CARD_SECRET', 'AUTO_CARD', '官方渠道批量采购', '虚拟资产存在交易风险，请确认后购买', 'APPROVED', NULL),
  (5, '游戏点券兑换码 500 点', '88.00', 300, 0, 0, 5, '500 点 | 秒发', 'https://images.unsplash.com/photo-1607083206968-13611e3d76db?auto=format&fit=crop&w=800&q=80', '<p>游戏点券兑换码 500 点</p><p>500 点 | 秒发</p>', 1, 2, 8, 'CARD_SECRET', 'AUTO_CARD', '官方渠道批量采购', '虚拟资产存在交易风险，请确认后购买', 'APPROVED', NULL),
  (6, '视频会员月卡激活码', '15.90', 800, 0, 0, 5, '月卡 | 全国通用', 'https://images.unsplash.com/photo-1607083206968-13611e3d76db?auto=format&fit=crop&w=800&q=80', '<p>视频会员月卡激活码</p><p>月卡 | 全国通用</p>', 1, 2, 8, 'CARD_SECRET', 'AUTO_CARD', '官方渠道批量采购', '虚拟资产存在交易风险，请确认后购买', 'APPROVED', NULL),
  (7, '稀有武器箱钥匙 x5', '45.00', 120, 0, 0, 6, '5 把 | 人工发放', 'https://images.unsplash.com/photo-1579783900882-c0d3dad7b119?auto=format&fit=crop&w=800&q=80', '<p>稀有武器箱钥匙 x5</p><p>5 把 | 人工发放</p>', 1, 3, 9, 'GAME_ITEM', 'MANUAL_DELIVERY', '活动余额', '虚拟资产存在交易风险，请确认后购买', 'APPROVED', NULL),
  (8, '限定皮肤兑换道具', '199.00', 40, 0, 0, 6, '限定兑换 | 人工发放', 'https://images.unsplash.com/photo-1579783900882-c0d3dad7b119?auto=format&fit=crop&w=800&q=80', '<p>限定皮肤兑换道具</p><p>限定兑换 | 人工发放</p>', 1, 3, 9, 'GAME_ITEM', 'MANUAL_DELIVERY', '活动余额', '虚拟资产存在交易风险，请确认后购买', 'APPROVED', NULL),
  (9, '全服喇叭礼包 x10', '30.00', 200, 0, 0, 6, '10 个 | 人工发放', 'https://images.unsplash.com/photo-1579783900882-c0d3dad7b119?auto=format&fit=crop&w=800&q=80', '<p>全服喇叭礼包 x10</p><p>10 个 | 人工发放</p>', 1, 3, 9, 'GAME_ITEM', 'MANUAL_DELIVERY', '活动余额', '虚拟资产存在交易风险，请确认后购买', 'APPROVED', NULL),
  (10, '王者荣耀国标账号 (V8)', '6800.00', 1, 1, 0, 7, 'V8 全英雄 | 支持改绑', 'https://images.unsplash.com/photo-1493711662062-fa541adb3fc8?auto=format&fit=crop&w=800&q=80', '<p>王者荣耀国标账号 (V8)</p><p>V8 全英雄 | 支持改绑</p>', 1, 4, 10, 'GAME_ACCOUNT', 'MANUAL_DELIVERY', '一手号，支持当面验号', '虚拟资产存在交易风险，请确认后购买', 'APPROVED', NULL),
  (11, '和平精英满级账号', '3200.00', 2, 0, 0, 7, '稀有皮肤多 | 支持改绑', 'https://images.unsplash.com/photo-1493711662062-fa541adb3fc8?auto=format&fit=crop&w=800&q=80', '<p>和平精英满级账号</p><p>稀有皮肤多 | 支持改绑</p>', 1, 4, 10, 'GAME_ACCOUNT', 'MANUAL_DELIVERY', '一手号，支持当面验号', '虚拟资产存在交易风险，请确认后购买', 'APPROVED', NULL),
  (12, '运动手套 | 迈阿密风云', '3999.00', 1, 0, 0, 2, '略有磨损 | 磨损 0.15', 'https://images.unsplash.com/photo-1542751371-adc38448a05e?auto=format&fit=crop&w=800&q=80', '<p>运动手套 | 迈阿密风云</p><p>略有磨损 | 磨损 0.15</p>', 1, 5, 11, 'VIRTUAL_SKIN', 'MANUAL_DELIVERY', '自用库存，支持视频验货', '虚拟资产存在交易风险，请确认后购买', 'APPROVED', NULL),
  (13, '蝴蝶刀 | 深红之网', '8600.00', 1, 1, 0, 3, '崭新出厂 | 磨损 0.03', 'https://images.unsplash.com/photo-1550745165-9bc0b252726f?auto=format&fit=crop&w=800&q=80', '<p>蝴蝶刀 | 深红之网</p><p>崭新出厂 | 磨损 0.03</p>', 1, 5, 11, 'VIRTUAL_SKIN', 'MANUAL_DELIVERY', '自用库存，支持视频验货', '虚拟资产存在交易风险，请确认后购买', 'APPROVED', NULL),
  (14, 'M4A1 | 二西莫夫 (崭新)', '459.00', 10, 0, 0, 4, '崭新出厂 | 磨损 0.05', 'https://images.unsplash.com/photo-1511512578047-dfb367046420?auto=format&fit=crop&w=800&q=80', '<p>M4A1 | 二西莫夫 (崭新)</p><p>崭新出厂 | 磨损 0.05</p>', 0, 5, 11, 'VIRTUAL_SKIN', 'MANUAL_DELIVERY', '自用库存', '虚拟资产存在交易风险，请确认后购买', 'PENDING', NULL),
  (15, 'AK-47 | 红线 (超低价)', '9.90', 5, 0, 0, 4, '离谱低价 | 疑似异常', 'https://images.unsplash.com/photo-1542751371-adc38448a05e?auto=format&fit=crop&w=800&q=80', '<p>AK-47 | 红线 (超低价)</p><p>离谱低价 | 疑似异常</p>', 0, 6, 12, 'VIRTUAL_SKIN', 'MANUAL_DELIVERY', '来源不明', '虚拟资产存在交易风险，请确认后购买', 'REJECTED', '价格明显异常且描述命中风险词'),
  (16, '王者荣耀代练上分服务', '200.00', 50, 0, 0, 8, '代练至王者段位', 'https://images.unsplash.com/photo-1579783900882-c0d3dad7b119?auto=format&fit=crop&w=800&q=80', '<p>王者荣耀代练上分服务</p><p>代练至王者段位</p>', 0, 7, 13, 'SERVICE', 'MANUAL_DELIVERY', '工作室服务', '虚拟资产存在交易风险，请确认后购买', 'PENDING', NULL),
  (17, '低价游戏币卡密', '1.00', 999, 0, 0, 6, '低价走量 | 秒到', 'https://images.unsplash.com/photo-1579783900882-c0d3dad7b119?auto=format&fit=crop&w=800&q=80', '<p>低价游戏币卡密</p><p>低价走量 | 秒到</p>', 0, 8, 15, 'GAME_ITEM', 'MANUAL_DELIVERY', '批量采购', '虚拟资产存在交易风险，请确认后购买', 'PENDING', NULL),
  (18, '火麒麟兑换码', '66.00', 200, 0, 0, 5, '官方渠道 | 自动发货', 'https://images.unsplash.com/photo-1607083206968-13611e3d76db?auto=format&fit=crop&w=800&q=80', '<p>火麒麟兑换码</p><p>官方渠道 | 自动发货</p>', 1, 8, 15, 'CARD_SECRET', 'AUTO_CARD', '批量采购', '虚拟资产存在交易风险，请确认后购买', 'APPROVED', NULL)
ON DUPLICATE KEY UPDATE `id` = `id`;

-- 商品审核记录
INSERT INTO `t_item_audit` (`item_id`, `action`, `auditor_id`, `reason`, `created_time`) VALUES
  (1, 'SUBMIT', NULL, '提交审核', '2026-09-11 10:00:00.000'),
  (1, 'APPROVE', 1, '商品信息与风险声明完整', '2026-09-11 10:20:00.000'),
  (2, 'SUBMIT', NULL, '提交审核', '2026-09-12 10:00:00.000'),
  (2, 'APPROVE', 1, '商品信息与风险声明完整', '2026-09-12 10:20:00.000'),
  (3, 'SUBMIT', NULL, '提交审核', '2026-09-13 10:00:00.000'),
  (3, 'APPROVE', 1, '商品信息与风险声明完整', '2026-09-13 10:20:00.000'),
  (4, 'SUBMIT', NULL, '提交审核', '2026-09-14 10:00:00.000'),
  (4, 'APPROVE', 1, '商品信息与风险声明完整', '2026-09-14 10:20:00.000'),
  (5, 'SUBMIT', NULL, '提交审核', '2026-09-10 10:00:00.000'),
  (5, 'APPROVE', 1, '商品信息与风险声明完整', '2026-09-10 10:20:00.000'),
  (6, 'SUBMIT', NULL, '提交审核', '2026-09-11 10:00:00.000'),
  (6, 'APPROVE', 1, '商品信息与风险声明完整', '2026-09-11 10:20:00.000'),
  (7, 'SUBMIT', NULL, '提交审核', '2026-09-12 10:00:00.000'),
  (7, 'APPROVE', 1, '商品信息与风险声明完整', '2026-09-12 10:20:00.000'),
  (8, 'SUBMIT', NULL, '提交审核', '2026-09-13 10:00:00.000'),
  (8, 'APPROVE', 1, '商品信息与风险声明完整', '2026-09-13 10:20:00.000'),
  (9, 'SUBMIT', NULL, '提交审核', '2026-09-14 10:00:00.000'),
  (9, 'APPROVE', 1, '商品信息与风险声明完整', '2026-09-14 10:20:00.000'),
  (10, 'SUBMIT', NULL, '提交审核', '2026-09-10 10:00:00.000'),
  (10, 'APPROVE', 1, '商品信息与风险声明完整', '2026-09-10 10:20:00.000'),
  (11, 'SUBMIT', NULL, '提交审核', '2026-09-11 10:00:00.000'),
  (11, 'APPROVE', 1, '商品信息与风险声明完整', '2026-09-11 10:20:00.000'),
  (12, 'SUBMIT', NULL, '提交审核', '2026-09-12 10:00:00.000'),
  (12, 'APPROVE', 1, '商品信息与风险声明完整', '2026-09-12 10:20:00.000'),
  (13, 'SUBMIT', NULL, '提交审核', '2026-09-13 10:00:00.000'),
  (13, 'APPROVE', 1, '商品信息与风险声明完整', '2026-09-13 10:20:00.000'),
  (14, 'SUBMIT', NULL, '提交审核', '2026-09-14 10:00:00.000'),
  (15, 'SUBMIT', NULL, '提交审核', '2026-09-10 10:00:00.000'),
  (15, 'REJECT', 1, '价格明显异常且描述命中风险词', '2026-09-10 10:20:00.000'),
  (16, 'SUBMIT', NULL, '提交审核', '2026-09-11 10:00:00.000'),
  (17, 'SUBMIT', NULL, '提交审核', '2026-09-12 10:00:00.000'),
  (18, 'SUBMIT', NULL, '提交审核', '2026-09-13 10:00:00.000'),
  (18, 'APPROVE', 1, '商品信息与风险声明完整', '2026-09-13 10:20:00.000');

-- 卡密库存（secret_cipher 为加密密文，secret_hash 为明文哈希，secret_mask 为脱敏展示）
INSERT INTO `t_card_secret` (`id`, `item_id`, `merchant_id`, `secret_cipher`, `secret_hash`, `secret_mask`, `status`, `order_no`, `reservation_no`, `locked_time`, `sold_time`, `created_time`, `updated_time`) VALUES
  (1, 4, 2, 'ENC:63f2ef3d2a164839e824a1f5', '63f2ef3d2a164839e824a1f5e044e1a22b8d112e1b7a3e344d2c714fd02b756b', 'CARD****CRET', 'SOLD', 'SO202609100003', 'RESSO202609100003', '2026-09-10 13:00:05.000', '2026-09-10 13:00:05.000', '2026-09-15 10:00:00.000', '2026-09-10 13:00:05.000'),
  (2, 4, 2, 'ENC:3aff04ebc66fbf2e318138d6', '3aff04ebc66fbf2e318138d670c8486d4e8f833603b77bbcbd493a866405f5f1', 'CARD****CRET', 'SOLD', 'SO202609250017', 'RESSO202609250017', '2026-09-25 16:00:05.000', '2026-09-25 16:00:05.000', '2026-09-15 10:00:00.000', '2026-09-25 16:00:05.000'),
  (3, 4, 2, 'ENC:06405525281ba0e0e723b54d', '06405525281ba0e0e723b54dc5ed81928d10b0fe43903d863e7a7d5d104b07a4', 'CARD****CRET', 'AVAILABLE', NULL, NULL, NULL, NULL, '2026-09-15 10:00:00.000', '2026-09-15 10:00:00.000'),
  (4, 4, 2, 'ENC:cfdc34ea62f29570b0b2702e', 'cfdc34ea62f29570b0b2702e7f4a6fbf0ac7d9114d70f547ba94311132d682b7', 'CARD****CRET', 'AVAILABLE', NULL, NULL, NULL, NULL, '2026-09-15 10:00:00.000', '2026-09-15 10:00:00.000'),
  (5, 4, 2, 'ENC:ff53c567deba5572649520d0', 'ff53c567deba5572649520d02c078a9dc1b142fd88976e7a186e337fd3bb5c5a', 'CARD****CRET', 'AVAILABLE', NULL, NULL, NULL, NULL, '2026-09-15 10:00:00.000', '2026-09-15 10:00:00.000'),
  (6, 4, 2, 'ENC:b7395893efa0f822f093bda3', 'b7395893efa0f822f093bda396371ae1739b93d869be233477abf040d064c804', 'CARD****CRET', 'AVAILABLE', NULL, NULL, NULL, NULL, '2026-09-15 10:00:00.000', '2026-09-15 10:00:00.000'),
  (7, 4, 2, 'ENC:61568fb8f58dee3d43e5f420', '61568fb8f58dee3d43e5f420b1a039c4dd7cc2e139cc0de1abd09110145e9530', 'CARD****CRET', 'AVAILABLE', NULL, NULL, NULL, NULL, '2026-09-15 10:00:00.000', '2026-09-15 10:00:00.000'),
  (8, 4, 2, 'ENC:1afa0deeed23d20e53d60f33', '1afa0deeed23d20e53d60f331800df2a5dcebbb90c01263982e52e04077e44e7', 'CARD****CRET', 'AVAILABLE', NULL, NULL, NULL, NULL, '2026-09-15 10:00:00.000', '2026-09-15 10:00:00.000'),
  (9, 4, 2, 'ENC:3e7986761b86e8de0bfc259a', '3e7986761b86e8de0bfc259a2d6c622449dccaa0de8dffdcef098579a52a124c', 'CARD****CRET', 'AVAILABLE', NULL, NULL, NULL, NULL, '2026-09-15 10:00:00.000', '2026-09-15 10:00:00.000'),
  (10, 4, 2, 'ENC:2a68f726a9528033e24cddca', '2a68f726a9528033e24cddcac9b40c5cf514e2709a3158336f080d58581adde1', 'CARD****CRET', 'AVAILABLE', NULL, NULL, NULL, NULL, '2026-09-15 10:00:00.000', '2026-09-15 10:00:00.000'),
  (11, 4, 2, 'ENC:0faf69e154fbd0cb72126e4e', '0faf69e154fbd0cb72126e4e1c2ad901ca4a01cca3f458b688ccc6ebe5349183', 'CARD****CRET', 'AVAILABLE', NULL, NULL, NULL, NULL, '2026-09-15 10:00:00.000', '2026-09-15 10:00:00.000'),
  (12, 4, 2, 'ENC:035aa005db2ce57f46ed78dc', '035aa005db2ce57f46ed78dcd31b27a76fa9b9319114013da98795377edfcbcd', 'CARD****CRET', 'AVAILABLE', NULL, NULL, NULL, NULL, '2026-09-15 10:00:00.000', '2026-09-15 10:00:00.000'),
  (13, 5, 2, 'ENC:38b468daf56f43c2122451bd', '38b468daf56f43c2122451bd07558bdbee41a8a6a2220b1d17abce566b47a97a', 'CARD****CRET', 'SOLD', 'SO202610050007', 'RESSO202610050007', '2026-10-05 09:05:00.000', '2026-10-05 09:05:00.000', '2026-09-15 10:00:00.000', '2026-10-05 09:05:00.000'),
  (14, 5, 2, 'ENC:87b4bcbcf9ae2e26580b5e7f', '87b4bcbcf9ae2e26580b5e7fc7db00ece02afd32d64ca5b09c5e0491bbb4fd5e', 'CARD****CRET', 'SOLD', 'SO202610050007', 'RESSO202610050007', '2026-10-05 09:05:00.000', '2026-10-05 09:05:00.000', '2026-09-15 10:00:00.000', '2026-10-05 09:05:00.000'),
  (15, 5, 2, 'ENC:0726a2993c3ece44d712b0fd', '0726a2993c3ece44d712b0fdd9c9469c362db94c20ae14310f5c281c7b22653e', 'CARD****CRET', 'AVAILABLE', NULL, NULL, NULL, NULL, '2026-09-15 10:00:00.000', '2026-09-15 10:00:00.000'),
  (16, 5, 2, 'ENC:21ee8987237f0c3ddf4939fd', '21ee8987237f0c3ddf4939fd686b34ccd85812b47b2eb2b6f3f295eddfcfd73a', 'CARD****CRET', 'AVAILABLE', NULL, NULL, NULL, NULL, '2026-09-15 10:00:00.000', '2026-09-15 10:00:00.000'),
  (17, 5, 2, 'ENC:4398f6e16ad206bbf3698f31', '4398f6e16ad206bbf3698f31761339dd3103c857f1fc5d440a323562051adb7f', 'CARD****CRET', 'AVAILABLE', NULL, NULL, NULL, NULL, '2026-09-15 10:00:00.000', '2026-09-15 10:00:00.000'),
  (18, 5, 2, 'ENC:48968f10fa64f8e670d6c30b', '48968f10fa64f8e670d6c30bff31544d2350d94df4496e20b19fc4aca871ea9c', 'CARD****CRET', 'AVAILABLE', NULL, NULL, NULL, NULL, '2026-09-15 10:00:00.000', '2026-09-15 10:00:00.000'),
  (19, 5, 2, 'ENC:038578220253937b4034455e', '038578220253937b4034455e16dc7105b2580458b324cec60c700b50a7fd4946', 'CARD****CRET', 'AVAILABLE', NULL, NULL, NULL, NULL, '2026-09-15 10:00:00.000', '2026-09-15 10:00:00.000'),
  (20, 5, 2, 'ENC:5cc53d22dd8d74e8e6496747', '5cc53d22dd8d74e8e6496747a4a9354d5f675343aebf0f8ecc355838bf2a68b7', 'CARD****CRET', 'AVAILABLE', NULL, NULL, NULL, NULL, '2026-09-15 10:00:00.000', '2026-09-15 10:00:00.000'),
  (21, 5, 2, 'ENC:d214a9f932cd675a9845fac0', 'd214a9f932cd675a9845fac033d3326d0e3e1837e1dc6152e4366b30ccd9abcb', 'CARD****CRET', 'AVAILABLE', NULL, NULL, NULL, NULL, '2026-09-15 10:00:00.000', '2026-09-15 10:00:00.000'),
  (22, 5, 2, 'ENC:bd518a4abb48a6a91fed656a', 'bd518a4abb48a6a91fed656a40cd507a95bfabd2191dfb20e1f7e14dced732c9', 'CARD****CRET', 'AVAILABLE', NULL, NULL, NULL, NULL, '2026-09-15 10:00:00.000', '2026-09-15 10:00:00.000'),
  (23, 6, 2, 'ENC:349e0d0820da3c56ca7428c1', '349e0d0820da3c56ca7428c1d5634b851e7cc597ce0b1dfd113a622b1e0aaec9', 'CARD****CRET', 'SOLD', 'SO202610060008', 'RESSO202610060008', '2026-10-08 07:05:00.000', '2026-10-08 07:05:00.000', '2026-09-15 10:00:00.000', '2026-10-08 07:05:00.000'),
  (24, 6, 2, 'ENC:236f974dbdcde1e72a2fc778', '236f974dbdcde1e72a2fc7783231506efd3a1b01bce0a6ba3f4d882eb88fa101', 'CARD****CRET', 'SOLD', 'SO202610060008', 'RESSO202610060008', '2026-10-08 07:05:00.000', '2026-10-08 07:05:00.000', '2026-09-15 10:00:00.000', '2026-10-08 07:05:00.000'),
  (25, 6, 2, 'ENC:00e0584989a4f5006e4bdc78', '00e0584989a4f5006e4bdc78b43fa3cf8202f689a1a5b417a565200cb4899430', 'CARD****CRET', 'SOLD', 'SO202610060008', 'RESSO202610060008', '2026-10-08 07:05:00.000', '2026-10-08 07:05:00.000', '2026-09-15 10:00:00.000', '2026-10-08 07:05:00.000'),
  (26, 6, 2, 'ENC:efe00f909644d2cf643d1197', 'efe00f909644d2cf643d119765d94c8b108433b2c810d992b1c49b2b63f250bc', 'CARD****CRET', 'SOLD', 'SO202610060008', 'RESSO202610060008', '2026-10-08 07:05:00.000', '2026-10-08 07:05:00.000', '2026-09-15 10:00:00.000', '2026-10-08 07:05:00.000'),
  (27, 6, 2, 'ENC:9ade1d4a5dd67c996bc13382', '9ade1d4a5dd67c996bc13382234f01a6a088ba8697366e07b8b48061983de6f6', 'CARD****CRET', 'SOLD', 'SO202610070015', 'RESSO202610070015', '2026-10-07 15:00:00.000', '2026-10-07 15:00:00.000', '2026-09-15 10:00:00.000', '2026-10-07 15:00:00.000'),
  (28, 6, 2, 'ENC:da2727d68341b926d25ab31a', 'da2727d68341b926d25ab31abebe2e3a580e50b9498dd22468e6a563732874f4', 'CARD****CRET', 'SOLD', 'SO202610070015', 'RESSO202610070015', '2026-10-07 15:00:00.000', '2026-10-07 15:00:00.000', '2026-09-15 10:00:00.000', '2026-10-07 15:00:00.000'),
  (29, 6, 2, 'ENC:620d6a898aa6d2c839f5ce17', '620d6a898aa6d2c839f5ce17bb7f924a9063adec0a555485761c45f5d585ec1a', 'CARD****CRET', 'INVALID', 'SO202610020021', 'RESSO202610020021', '2026-10-03 15:05:00.000', '2026-10-03 15:05:00.000', '2026-09-15 10:00:00.000', '2026-10-03 15:05:00.000'),
  (30, 6, 2, 'ENC:200be34daa8f1d7c5cfc9ebc', '200be34daa8f1d7c5cfc9ebc3d5a132d5b771043fb1a469da58ec7208e56eeac', 'CARD****CRET', 'INVALID', 'SO202610020021', 'RESSO202610020021', '2026-10-03 15:05:00.000', '2026-10-03 15:05:00.000', '2026-09-15 10:00:00.000', '2026-10-03 15:05:00.000'),
  (31, 6, 2, 'ENC:f98d9d5475015d185d57fdd4', 'f98d9d5475015d185d57fdd4301812abca567b80480fda5948718c72a2a67a08', 'CARD****CRET', 'AVAILABLE', NULL, NULL, NULL, NULL, '2026-09-15 10:00:00.000', '2026-09-15 10:00:00.000'),
  (32, 6, 2, 'ENC:8111f3c636b5aafb44438bfe', '8111f3c636b5aafb44438bfed9c0d5bf9081ad3bf55324c910d701adb4fbc1ef', 'CARD****CRET', 'AVAILABLE', NULL, NULL, NULL, NULL, '2026-09-15 10:00:00.000', '2026-09-15 10:00:00.000'),
  (33, 6, 2, 'ENC:7a6bc3effbfb067666a6d659', '7a6bc3effbfb067666a6d65920699c5ad2566fdc16552e5b736404fd704cbaf5', 'CARD****CRET', 'AVAILABLE', NULL, NULL, NULL, NULL, '2026-09-15 10:00:00.000', '2026-09-15 10:00:00.000'),
  (34, 6, 2, 'ENC:8900f1b5d097318abba22627', '8900f1b5d097318abba22627b9ca1077afd0bbc154d062fe8140afaea2de1d8a', 'CARD****CRET', 'AVAILABLE', NULL, NULL, NULL, NULL, '2026-09-15 10:00:00.000', '2026-09-15 10:00:00.000'),
  (35, 18, 8, 'ENC:412d679d7b3e77d26bd9e24d', '412d679d7b3e77d26bd9e24da01590305ac363e60078ab7b15a5bc21b209502b', 'CARD****CRET', 'SOLD', 'SO202609280019', 'RESSO202609280019', '2026-09-28 10:00:08.000', '2026-09-28 10:00:08.000', '2026-09-15 10:00:00.000', '2026-09-28 10:00:08.000'),
  (36, 18, 8, 'ENC:d9da402cbf51427ccb14e462', 'd9da402cbf51427ccb14e4627a5ee382f5bacf3f2e30789ecfcc23fcc26abc45', 'CARD****CRET', 'SOLD', 'SO202610080020', 'RESSO202610080020', '2026-10-08 06:00:00.000', '2026-10-08 06:00:00.000', '2026-09-15 10:00:00.000', '2026-10-08 06:00:00.000'),
  (37, 18, 8, 'ENC:be2d225420afb734d6b4d14d', 'be2d225420afb734d6b4d14db9fe50c6dca8e52a180ad3ea616cce7c51a13b9f', 'CARD****CRET', 'AVAILABLE', NULL, NULL, NULL, NULL, '2026-09-15 10:00:00.000', '2026-09-15 10:00:00.000'),
  (38, 18, 8, 'ENC:b4e1a37bfdb0950175b6c722', 'b4e1a37bfdb0950175b6c7229cf79b4817992317dd361ee4c58a1521a2bc3435', 'CARD****CRET', 'AVAILABLE', NULL, NULL, NULL, NULL, '2026-09-15 10:00:00.000', '2026-09-15 10:00:00.000'),
  (39, 18, 8, 'ENC:998eeeb8cf4c298383d02433', '998eeeb8cf4c298383d024339aa71224b5647b5fd5aad913f38029273256540f', 'CARD****CRET', 'AVAILABLE', NULL, NULL, NULL, NULL, '2026-09-15 10:00:00.000', '2026-09-15 10:00:00.000'),
  (40, 18, 8, 'ENC:aa0c9dd176af7a32ee7a4b6b', 'aa0c9dd176af7a32ee7a4b6b32664b314571d335cb5dc23fd221ac71f53f4211', 'CARD****CRET', 'AVAILABLE', NULL, NULL, NULL, NULL, '2026-09-15 10:00:00.000', '2026-09-15 10:00:00.000'),
  (41, 18, 8, 'ENC:59cf6fcd18ddeb4d20e38e82', '59cf6fcd18ddeb4d20e38e8296075979de101087d02df1b7208cd23831f21c3f', 'CARD****CRET', 'AVAILABLE', NULL, NULL, NULL, NULL, '2026-09-15 10:00:00.000', '2026-09-15 10:00:00.000'),
  (42, 18, 8, 'ENC:0e07bf820d6eabef46b62d48', '0e07bf820d6eabef46b62d480c0fa763471b4c04da90abc8722720f8bfb64182', 'CARD****CRET', 'AVAILABLE', NULL, NULL, NULL, NULL, '2026-09-15 10:00:00.000', '2026-09-15 10:00:00.000')
ON DUPLICATE KEY UPDATE `id` = `id`;

-- 交易资产预留
INSERT INTO `t_asset_reservation` (`reservation_no`, `order_no`, `idempotency_key`, `snapshot_json`, `item_id`, `merchant_id`, `asset_type`, `quantity`, `card_secret_ids`, `status`, `expire_time`, `created_time`, `updated_time`) VALUES
  ('RESSO202609100001', 'SO202609100001', 'ASSET_RESERVE:SO202609100001', '{"itemId":1,"itemName":"AK-47 | 红线","price":"1288.00","merchantId":1,"sellerId":7,"assetType":"VIRTUAL_SKIN","deliveryMode":"MANUAL_DELIVERY","categoryId":4}', 1, 1, 'VIRTUAL_SKIN', 1, NULL, 'CONFIRMED', '2026-09-10 10:15:00.000', '2026-09-10 10:00:00.000', '2026-09-12 09:05:00.000'),
  ('RESSO202609100002', 'SO202609100002', 'ASSET_RESERVE:SO202609100002', '{"itemId":2,"itemName":"M4A1 消音版 | 二西莫夫","price":"356.00","merchantId":1,"sellerId":7,"assetType":"VIRTUAL_SKIN","deliveryMode":"MANUAL_DELIVERY","categoryId":4}', 2, 1, 'VIRTUAL_SKIN', 2, NULL, 'CONFIRMED', '2026-09-10 11:15:00.000', '2026-09-10 10:55:00.000', '2026-09-12 20:03:00.000'),
  ('RESSO202609100003', 'SO202609100003', 'ASSET_RESERVE:SO202609100003', '{"itemId":4,"itemName":"Steam 钱包充值卡密","price":"96.00","merchantId":2,"sellerId":8,"assetType":"CARD_SECRET","deliveryMode":"AUTO_CARD","categoryId":5}', 4, 2, 'CARD_SECRET', 1, '[1]', 'CONFIRMED', '2026-09-10 13:10:00.000', '2026-09-10 12:58:00.000', '2026-09-11 13:10:00.000'),
  ('RESSO202609100004', 'SO202609100004', 'ASSET_RESERVE:SO202609100004', '{"itemId":7,"itemName":"稀有武器箱钥匙 x5","price":"45.00","merchantId":3,"sellerId":9,"assetType":"GAME_ITEM","deliveryMode":"MANUAL_DELIVERY","categoryId":6}', 7, 3, 'GAME_ITEM', 3, NULL, 'CONFIRMED', '2026-09-10 15:15:00.000', '2026-09-10 14:55:00.000', '2026-09-12 10:02:00.000'),
  ('RESSO202609110005', 'SO202609110005', 'ASSET_RESERVE:SO202609110005', '{"itemId":10,"itemName":"王者荣耀国标账号 (V8)","price":"6800.00","merchantId":4,"sellerId":10,"assetType":"GAME_ACCOUNT","deliveryMode":"MANUAL_DELIVERY","categoryId":7}', 10, 4, 'GAME_ACCOUNT', 1, NULL, 'CONFIRMED', '2026-09-11 09:15:00.000', '2026-09-11 08:55:00.000', '2026-09-13 10:00:00.000'),
  ('RESSO202609120006', 'SO202609120006', 'ASSET_RESERVE:SO202609120006', '{"itemId":12,"itemName":"运动手套 | 迈阿密风云","price":"3999.00","merchantId":5,"sellerId":11,"assetType":"VIRTUAL_SKIN","deliveryMode":"MANUAL_DELIVERY","categoryId":2}', 12, 5, 'VIRTUAL_SKIN', 1, NULL, 'CONFIRMED', '2026-09-12 14:15:00.000', '2026-09-12 13:55:00.000', '2026-09-14 12:05:00.000'),
  ('RESSO202610050007', 'SO202610050007', 'ASSET_RESERVE:SO202610050007', '{"itemId":5,"itemName":"游戏点券兑换码 500 点","price":"88.00","merchantId":2,"sellerId":8,"assetType":"CARD_SECRET","deliveryMode":"AUTO_CARD","categoryId":5}', 5, 2, 'CARD_SECRET', 2, '[13,14]', 'CONFIRMED', '2026-10-05 09:15:00.000', '2026-10-05 08:55:00.000', '2026-10-05 09:05:00.000'),
  ('RESSO202610060008', 'SO202610060008', 'ASSET_RESERVE:SO202610060008', '{"itemId":6,"itemName":"视频会员月卡激活码","price":"15.90","merchantId":2,"sellerId":8,"assetType":"CARD_SECRET","deliveryMode":"AUTO_CARD","categoryId":5}', 6, 2, 'CARD_SECRET', 4, '[23,24,25,26]', 'CONFIRMED', '2026-10-08 07:15:00.000', '2026-10-08 06:55:00.000', '2026-10-08 07:05:00.000'),
  ('RESSO202610080009', 'SO202610080009', 'ASSET_RESERVE:SO202610080009', '{"itemId":8,"itemName":"限定皮肤兑换道具","price":"199.00","merchantId":3,"sellerId":9,"assetType":"GAME_ITEM","deliveryMode":"MANUAL_DELIVERY","categoryId":6}', 8, 3, 'GAME_ITEM', 1, NULL, 'CONFIRMED', '2026-10-08 09:15:00.000', '2026-10-08 08:55:00.000', '2026-10-08 08:55:00.000'),
  ('RESSO202609180010', 'SO202609180010', 'ASSET_RESERVE:SO202609180010', '{"itemId":12,"itemName":"运动手套 | 迈阿密风云","price":"3999.00","merchantId":5,"sellerId":11,"assetType":"VIRTUAL_SKIN","deliveryMode":"MANUAL_DELIVERY","categoryId":2}', 12, 5, 'VIRTUAL_SKIN', 1, NULL, 'REFUNDED', '2026-09-18 09:15:00.000', '2026-09-18 08:55:00.000', '2026-09-20 15:05:00.000'),
  ('RESSO202609180011', 'SO202609180011', 'ASSET_RESERVE:SO202609180011', '{"itemId":3,"itemName":"折刀 | 渐变之色","price":"2680.00","merchantId":1,"sellerId":7,"assetType":"VIRTUAL_SKIN","deliveryMode":"MANUAL_DELIVERY","categoryId":3}', 3, 1, 'VIRTUAL_SKIN', 1, NULL, 'REFUNDED', '2026-09-18 11:15:00.000', '2026-09-18 10:55:00.000', '2026-09-18 11:32:00.000'),
  ('RESSO202609190012', 'SO202609190012', 'ASSET_RESERVE:SO202609190012', '{"itemId":11,"itemName":"和平精英满级账号","price":"3200.00","merchantId":4,"sellerId":10,"assetType":"GAME_ACCOUNT","deliveryMode":"MANUAL_DELIVERY","categoryId":7}', 11, 4, 'GAME_ACCOUNT', 1, NULL, 'CONFIRMED', '2026-09-19 09:15:00.000', '2026-09-19 08:55:00.000', '2026-09-21 10:04:00.000'),
  ('RESSO202609200013', 'SO202609200013', 'ASSET_RESERVE:SO202609200013', '{"itemId":9,"itemName":"全服喇叭礼包 x10","price":"30.00","merchantId":3,"sellerId":9,"assetType":"GAME_ITEM","deliveryMode":"MANUAL_DELIVERY","categoryId":6}', 9, 3, 'GAME_ITEM', 5, NULL, 'RELEASED', '2026-09-20 09:15:00.000', '2026-09-20 09:00:00.000', '2026-09-20 09:16:00.000'),
  ('RESSO202610080014', 'SO202610080014', 'ASSET_RESERVE:SO202610080014', '{"itemId":2,"itemName":"M4A1 消音版 | 二西莫夫","price":"356.00","merchantId":1,"sellerId":7,"assetType":"VIRTUAL_SKIN","deliveryMode":"MANUAL_DELIVERY","categoryId":4}', 2, 1, 'VIRTUAL_SKIN', 1, NULL, 'RESERVED', '2026-10-08 09:25:00.000', '2026-10-08 09:10:00.000', '2026-10-08 09:10:00.000'),
  ('RESSO202610070015', 'SO202610070015', 'ASSET_RESERVE:SO202610070015', '{"itemId":6,"itemName":"视频会员月卡激活码","price":"15.90","merchantId":2,"sellerId":8,"assetType":"CARD_SECRET","deliveryMode":"AUTO_CARD","categoryId":5}', 6, 2, 'CARD_SECRET', 2, '[27,28]', 'CONFIRMED', '2026-10-07 15:05:00.000', '2026-10-07 14:45:00.000', '2026-10-07 15:00:00.000'),
  ('RESSO202610040016', 'SO202610040016', 'ASSET_RESERVE:SO202610040016', '{"itemId":7,"itemName":"稀有武器箱钥匙 x5","price":"45.00","merchantId":3,"sellerId":9,"assetType":"GAME_ITEM","deliveryMode":"MANUAL_DELIVERY","categoryId":6}', 7, 3, 'GAME_ITEM', 10, NULL, 'CONFIRMED', '2026-10-04 10:15:00.000', '2026-10-04 09:55:00.000', '2026-10-04 10:20:00.000'),
  ('RESSO202609250017', 'SO202609250017', 'ASSET_RESERVE:SO202609250017', '{"itemId":4,"itemName":"Steam 钱包充值卡密","price":"96.00","merchantId":2,"sellerId":8,"assetType":"CARD_SECRET","deliveryMode":"AUTO_CARD","categoryId":5}', 4, 2, 'CARD_SECRET', 1, '[2]', 'CONFIRMED', '2026-09-25 16:15:00.000', '2026-09-25 15:55:00.000', '2026-09-27 09:03:00.000'),
  ('RESSO202609280019', 'SO202609280019', 'ASSET_RESERVE:SO202609280019', '{"itemId":18,"itemName":"火麒麟兑换码","price":"66.00","merchantId":8,"sellerId":15,"assetType":"CARD_SECRET","deliveryMode":"AUTO_CARD","categoryId":5}', 18, 8, 'CARD_SECRET', 1, '[35]', 'CONFIRMED', '2026-09-28 10:15:00.000', '2026-09-28 09:55:00.000', '2026-09-29 10:05:00.000'),
  ('RESSO202610080020', 'SO202610080020', 'ASSET_RESERVE:SO202610080020', '{"itemId":18,"itemName":"火麒麟兑换码","price":"66.00","merchantId":8,"sellerId":15,"assetType":"CARD_SECRET","deliveryMode":"AUTO_CARD","categoryId":5}', 18, 8, 'CARD_SECRET', 1, '[36]', 'CONFIRMED', '2026-10-08 06:15:00.000', '2026-10-08 05:55:00.000', '2026-10-08 06:00:00.000'),
  ('RESSO202610020021', 'SO202610020021', 'ASSET_RESERVE:SO202610020021', '{"itemId":6,"itemName":"视频会员月卡激活码","price":"15.90","merchantId":2,"sellerId":8,"assetType":"CARD_SECRET","deliveryMode":"AUTO_CARD","categoryId":5}', 6, 2, 'CARD_SECRET', 2, '[29,30]', 'REFUNDED', '2026-10-02 09:15:00.000', '2026-10-02 08:55:00.000', '2026-10-03 15:05:00.000'),
  ('RESSO202610030022', 'SO202610030022', 'ASSET_RESERVE:SO202610030022', '{"itemId":10,"itemName":"王者荣耀国标账号 (V8)","price":"6800.00","merchantId":4,"sellerId":10,"assetType":"GAME_ACCOUNT","deliveryMode":"MANUAL_DELIVERY","categoryId":7}', 10, 4, 'GAME_ACCOUNT', 1, NULL, 'RESERVED', '2026-10-03 10:15:00.000', '2026-10-03 10:00:00.000', '2026-10-03 10:00:00.000'),
  ('RESSO202610080023', 'SO202610080023', 'ASSET_RESERVE:SO202610080023', '{"itemId":13,"itemName":"蝴蝶刀 | 深红之网","price":"8600.00","merchantId":5,"sellerId":11,"assetType":"VIRTUAL_SKIN","deliveryMode":"MANUAL_DELIVERY","categoryId":3}', 13, 5, 'VIRTUAL_SKIN', 1, NULL, 'RESERVED', '2026-10-08 09:30:00.000', '2026-10-08 09:12:00.000', '2026-10-08 09:12:00.000'),
  ('RESSO202610080024', 'SO202610080024', 'ASSET_RESERVE:SO202610080024', '{"itemId":1,"itemName":"AK-47 | 红线","price":"1288.00","merchantId":1,"sellerId":7,"assetType":"VIRTUAL_SKIN","deliveryMode":"MANUAL_DELIVERY","categoryId":4}', 1, 1, 'VIRTUAL_SKIN', 1, NULL, 'RESERVED', '2026-10-08 09:35:00.000', '2026-10-08 09:20:00.000', '2026-10-08 09:20:00.000')
ON DUPLICATE KEY UPDATE `reservation_no` = `reservation_no`;
