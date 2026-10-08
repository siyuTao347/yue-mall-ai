# RAG 初始语料 · 商品介绍文档

本目录为平台商品知识库（RAG）的初始文档集合，按「一个商品一份文档」组织，用于智能客服问答、商品发布助手与仲裁助手的检索召回。

## 使用说明

- 每份文档均为独立 Markdown，顶部带 YAML 元数据（`doc_id` / `category` / `asset_type` / `delivery_mode` / `merchant` / `price_cny` / `audit_status` / `shelf_status` / `tags`），适合按元数据过滤 + 向量检索。
- 正文分为 12 个固定小节：文档控制、商品概述、基础信息、商品亮点、来源与合规、交付方式、价格与费用、担保交易流程、风险提示、售后与争议、FAQ、检索标签。
- 建议切分策略：按二级标题（`##`）切分，FAQ 段按「问 + 答」成对切分，元数据随分块冗余写入。
- 标注为「已驳回 / 未上架 / 人工复核」的文档为风控与审核场景语料，不代表可售商品。

## 文档清单

| 文档编号 | 商品 | 类目 | 资产类型 | 交付方式 | 标价(元) | 审核状态 | 上架状态 | 文件 |
|---|---|---|---|---|---|---|---|---|
| PROD-001 | AK-47 \| 红线 | 虚拟饰品-枪械皮肤 | VIRTUAL_SKIN | MANUAL_DELIVERY | 1288.00 | 审核通过 | 已上架 | `PROD-001_AK-47-红线.md` |
| PROD-002 | M4A1 消音版 \| 二西莫夫 | 虚拟饰品-枪械皮肤 | VIRTUAL_SKIN | MANUAL_DELIVERY | 356.00 | 审核通过 | 已上架 | `PROD-002_M4A1消音版-二西莫夫.md` |
| PROD-003 | 折刀 \| 渐变之色 | 虚拟饰品-匕首 | VIRTUAL_SKIN | MANUAL_DELIVERY | 2680.00 | 审核通过 | 已上架 | `PROD-003_折刀-渐变之色.md` |
| PROD-004 | Steam 钱包充值卡密（100 元面值） | 卡密/激活码 | CARD_SECRET | AUTO_CARD | 96.00 | 审核通过 | 已上架 | `PROD-004_Steam钱包充值卡密100元.md` |
| PROD-005 | 游戏点券兑换码（500 点） | 卡密/激活码 | CARD_SECRET | AUTO_CARD | 88.00 | 审核通过 | 已上架 | `PROD-005_游戏点券兑换码500点.md` |
| PROD-006 | 视频会员月卡激活码 | 卡密/激活码 | CARD_SECRET | AUTO_CARD | 15.90 | 审核通过 | 已上架 | `PROD-006_视频会员月卡激活码.md` |
| PROD-007 | 稀有武器箱钥匙 x5 | 游戏道具 | GAME_ITEM | MANUAL_DELIVERY | 45.00 | 审核通过 | 已上架 | `PROD-007_稀有武器箱钥匙x5.md` |
| PROD-008 | 限定皮肤兑换道具 | 游戏道具 | GAME_ITEM | MANUAL_DELIVERY | 199.00 | 审核通过 | 已上架 | `PROD-008_限定皮肤兑换道具.md` |
| PROD-009 | 全服喇叭礼包 x10 | 游戏道具 | GAME_ITEM | MANUAL_DELIVERY | 30.00 | 审核通过 | 已上架 | `PROD-009_全服喇叭礼包x10.md` |
| PROD-010 | 王者荣耀国标账号（V8） | 游戏账号 | GAME_ACCOUNT | MANUAL_DELIVERY | 6800.00 | 审核通过 | 已上架 | `PROD-010_王者荣耀国标账号V8.md` |
| PROD-011 | 和平精英满级账号 | 游戏账号 | GAME_ACCOUNT | MANUAL_DELIVERY | 3200.00 | 审核通过 | 已上架 | `PROD-011_和平精英满级账号.md` |
| PROD-012 | 运动手套 \| 迈阿密风云 | 虚拟饰品-手套 | VIRTUAL_SKIN | MANUAL_DELIVERY | 3999.00 | 审核通过 | 已上架 | `PROD-012_运动手套-迈阿密风云.md` |
| PROD-013 | 蝴蝶刀 \| 深红之网 | 虚拟饰品-匕首 | VIRTUAL_SKIN | MANUAL_DELIVERY | 8600.00 | 审核通过 | 已上架 | `PROD-013_蝴蝶刀-深红之网.md` |
| PROD-014 | M4A1 \| 二西莫夫（崭新） | 虚拟饰品-枪械皮肤 | VIRTUAL_SKIN | MANUAL_DELIVERY | 459.00 | 审核中 | 未上架 | `PROD-014_M4A1-二西莫夫崭新.md` |
| PROD-015 | AK-47 \| 红线（超低价） | 虚拟饰品-枪械皮肤 | VIRTUAL_SKIN | MANUAL_DELIVERY | 9.90 | 已驳回 | 已下架 | `PROD-015_AK-47-红线超低价.md` |
| PROD-016 | 王者荣耀代练上分服务 | 代练/陪玩服务 | SERVICE | MANUAL_DELIVERY | 200.00 | 审核中 | 未上架 | `PROD-016_王者荣耀代练上分服务.md` |
| PROD-017 | 低价游戏币卡密 | 游戏道具 | GAME_ITEM | MANUAL_DELIVERY | 1.00 | 审核中 | 未上架 | `PROD-017_低价游戏币卡密.md` |
| PROD-018 | 火麒麟兑换码 | 卡密/激活码 | CARD_SECRET | AUTO_CARD | 66.00 | 审核通过 | 已上架 | `PROD-018_火麒麟兑换码.md` |

## 统计

| 类目 | 商品数 |
|---|---|
| 虚拟饰品-枪械皮肤 | 4 |
| 虚拟饰品-匕首 | 2 |
| 卡密/激活码 | 4 |
| 游戏道具 | 4 |
| 游戏账号 | 2 |
| 虚拟饰品-手套 | 1 |
| 代练/陪玩服务 | 1 |

- 文档总数：18 份
- 可售商品：14 份；待审核：3 份；已驳回：1 份
- 交付方式：自动发货 4 份，人工交付 14 份

## 与交易数据的关系

文档中的商品编号（`ITEM-0001` ~ `ITEM-0018`）与 `doc/data/MySQL/02_stage1_db_item.sql` 中 `t_item` 的商品主键一一对应，价格、库存、审核状态、交付方式均与该脚本保持一致，便于检索结果与数据库记录交叉校验。
