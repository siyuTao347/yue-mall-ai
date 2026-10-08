# 演示数据（阶段一 / 阶段二）

本目录存放虚拟资产担保交易平台的**数据库演示数据**，覆盖阶段一（业务闭环）和阶段二（风控闭环），可直接导入本地 MySQL 用于前后端联调与演示。

数据全部为脚本生成的确定性数据，账户余额、资金流水、订单状态、卡密库存、风控事件与案件之间相互对账，不是零散随机值。

---

## 1. 加载顺序

数据文件依赖建表脚本，请严格按以下顺序执行：

```text
1) db.sql                                  # 基础库：db_item / db_order / db_user
2) doc/sql/stage1_schema.sql               # 阶段一增量建表
3) doc/data/01_stage1_db_user.sql
4) doc/data/02_stage1_db_item.sql
5) doc/data/03_stage1_db_order.sql
6) doc/sql/stage2_schema.sql               # 阶段二建表 + 风控规则/敏感词初始化
   （如启用查询优化与运营闭环，再执行 stage2_batch2_*.sql、stage2_batch3_*.sql）
7) doc/data/04_stage2_db_risk.sql
8) doc/data/05_stage2_risk_flags.sql       # 业务表风险标记回填
```

> `04_stage2_db_risk.sql` 通过 `rule_code` 子查询引用 `t_risk_rule`，因此必须在 `stage2_schema.sql` 之后执行。
> `05_stage2_risk_flags.sql` 依赖阶段二给业务表新增的 `risk_status` 等字段。

所有脚本使用显式主键，并带 `ON DUPLICATE KEY UPDATE` / 无条件 `WHERE`，可重复执行。

---

## 2. 文件清单

| 文件 | 数据库 | 内容 |
|---|---|---|
| `01_stage1_db_user.sql` | `db_user` | 用户、商家、商家审核、保证金、资金账户、平台账户、手续费配置、商家信用、资金交易与流水、提现、资金对账差异 |
| `02_stage1_db_item.sql` | `db_item` | 类目、商品、商品审核、卡密库存、资产预留 |
| `03_stage1_db_order.sql` | `db_order` | 担保订单、状态日志、Mock 支付单、支付回调、防重放 nonce、编排任务与步骤、交付记录与证据、争议、仲裁、结算、评价、交易对账差异 |
| `04_stage2_db_risk.sql` | `db_risk` | 风控规则变更日志、风控主体、事件、决策、案件、案件记录、设备/IP 关系、关系边、指标物化、指标刷新任务 |
| `05_stage2_risk_flags.sql` | `db_item` / `db_order` / `db_user` | 回填商品、订单、商家、提现单的风险状态与延迟观察期 |

---

## 3. 演示账号

统一密码：`VALOR_123456`（BCrypt 加密）

| 角色 | 邮箱 | 说明 |
|---|---|---|
| 平台管理员 | `admin@mall.dev` | 后台审核、仲裁、风控案件处置 |
| 买家 | `buyer01@mall.dev` ~ `buyer05@mall.dev` | 正常买家，含多状态订单 |
| 风险买家 | `buyer06@mall.dev` | 高频下单、同设备多账号，命中风控 |
| 卖家 | `seller01@mall.dev` ~ `seller08@mall.dev` | 6 个已通过商家 + 1 个驳回 + 1 个冻结 |

---

## 4. 数据规模

| 数据库 | 主要表数据量 |
|---|---|
| `db_user` | 用户 15、商家 8、商家审核 18、保证金账户 7、用户资金账户 14、手续费配置 24、信用记录 8、资金交易 57、资金流水 127、提现 8、对账差异 3 |
| `db_item` | 类目 8、商品 18、商品审核 33、卡密 42、资产预留 23 |
| `db_order` | 订单 24、状态日志 168、支付单 22、支付回调 36、防重放 18、编排任务 5 / 步骤 21、交付记录 16、交付证据 27、争议 3、仲裁 2、结算 10、评价 10、对账差异 2 |
| `db_risk` | 主体 18、事件 15、决策 14、案件 8、案件记录 23、设备关系 8、IP 关系 8、关系边 6、指标 21、指标刷新任务 3 |

订单覆盖状态：`CREATE_PENDING`、`WAIT_PAY`、`PAY_CONFIRMING`、`PAID`、`DELIVERED`、`CONFIRMED`、`SETTLING`、`SETTLED`、`REFUNDED`、`CANCELLED`、`REJECTED`。

---

## 5. 数据一致性口径

以下是这批数据的核心约束，导入后可直接用于对账演示：

- **资金账本双分录**：每笔 `t_fund_transaction` 都配套 `t_fund_flow` 借贷流水，`balance_after` 为按时间重放得到的真实账户余额。
- **账户余额等于流水累计**：`t_user_account`、`t_platform_account` 的数值由流水逐笔累计得出，不存在凭空余额。
- **托管余额**：`t_platform_account.escrow_amount = 986.40`，等于所有在途订单（`PAID` / `DELIVERED` / `CONFIRMED` / `SETTLING`）订单金额之和。
- **平台收入**：`t_platform_account.revenue_amount = 327.84`，等于所有已结算订单手续费之和（费率 2%）。
- **结算口径**：仅 `SETTLED` / `SETTLING` 订单写入手续费与卖家收入，其余订单金额为 0；`SETTLING` 订单对应 `t_order_settlement.status = PENDING`。
- **卡密状态**：`SOLD` 卡密与自动交付订单一一对应，`INVALID` 卡密对应退款订单，`AVAILABLE` 为可售库存。
- **状态机**：订单的支付、交付、托管、售后状态组合均取自代码枚举允许的流转路径，状态日志完整记录流转轨迹。
- **提现闭环**：`PAYOUT_SUCCESS` 提现包含冻结 + 打款流水；`REJECTED` 包含冻结 + 驳回返还；`MANUAL_REVIEW` / `FROZEN` 仅冻结并在阶段二标记风险状态。
- **风控闭环**：`事件 -> 决策 -> 案件 -> 处置命令` 全链路可追踪，`t_risk_case_note` 记录建案、认领、处置与命令结果。
- **对账差异**：`t_trade_reconciliation_diff`、`t_fund_reconciliation_diff` 保留了 5 条差异样本（中断任务、风控提现口径差异），用于演示人工对账处理。

---

## 6. 重置方式

如需重新导入，按库清空后重跑即可：

```sql
SET FOREIGN_KEY_CHECKS = 0;
TRUNCATE TABLE db_user.t_user;         -- 或直接 DROP DATABASE 后重建
SET FOREIGN_KEY_CHECKS = 1;
```

建议做法：删除 `db_user` / `db_item` / `db_order` / `db_risk` 四个库，从 `db.sql` 重新按第 1 节顺序执行。
