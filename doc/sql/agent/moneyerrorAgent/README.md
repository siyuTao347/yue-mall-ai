# 资金异常调查 Agent 数据库脚本

本目录存放实现 Money Agent 所需的实际 SQL 文件，目标为 MySQL 8.0。依据为[数据契约与数据库设计](../../../agent/moneyErrorAgent/data_contracts_and_schema.md)。这些文件已生成，尚未在 MySQL 中执行；业务代码与数据库部署仍待实施。

共 6 份建表/种子脚本、1 份只读核对脚本。建表脚本覆盖 26 张表：调查库 15 张、客服库 6 张、订单库 3 张（其中修复命令表属于 M5）、用户资金库 2 张。意图种子包含 3 个定义和 9 条训练示例，默认禁用。

## 1. 文件与执行顺序

先确认现有 `db_order`、`db_user`、`db_agent` 已初始化，再按以下顺序执行当前阶段需要的脚本。各库的建表互不使用跨库外键；下表顺序与业务开发依赖一致。

| 顺序 | 文件 | 目标库 | 内容/阶段 |
| --- | --- | --- | --- |
| 1 | [money_error_v1_db_user_fund_business_ref.sql](money_error_v1_db_user_fund_business_ref.sql) | db_user | 资金业务引用与资金源事件 Outbox；M1/M4 |
| 2 | [money_error_v1_db_order_money_query.sql](money_error_v1_db_order_money_query.sql) | db_order | 渠道观察记录与订单源事件 Outbox；M1/M4 |
| 3 | [money_error_v1_db_money_agent_core.sql](money_error_v1_db_money_agent_core.sql) | db_money_agent | 创建调查库及 15 张运行时表；M2/M4 |
| 4 | [money_error_v1_db_agent_money_bridge.sql](money_error_v1_db_agent_money_bridge.sql) | db_agent | 客服绑定、提交、投递、卡片、通知 Inbox、坐席便笺；M3 |
| 5 | [money_error_v1_customer_money_intents.sql](money_error_v1_customer_money_intents.sql) | db_agent | 三类资金意图及训练示例；M3，默认禁用 |
| 6（后续） | [money_error_v2_money_repair_command.sql](money_error_v2_money_repair_command.sql) | db_order | 审核后修复命令台账；仅 M5，不属于首版启用范围 |
| 核对 | [money_error_verify.sql](money_error_verify.sql) | 只读跨库元数据及意图种子 | 检查表存在性、字段、全部 35 项唯一约束、种子与旧业务索引 |

无需按文件名字典顺序执行整个目录。M1 只需前两项；M2 增加调查库；M3 增加客服桥接与种子。M4 对源事件的可靠发布还需要服务端事务与 Worker 实现，单独建好 Outbox 不会开始发布事件。

## 2. 前置结构

- Order：先确认现有支付尝试、退款、金融事件及对账结构已就绪。参考 [v1 支付尝试](../../reconciliation/v1_payment_attempt_and_mock_channel.sql)、[v2 退款挂账](../../reconciliation/v2_payment_refund_and_fund_suspense.sql)、[v3 对账](../../reconciliation/v3_channel_settlement_and_reconciliation.sql)。这些旧脚本包含已有表变更，不能为了本功能重复执行全部历史迁移。
- User：现有资金账户、`t_fund_transaction`、`t_fund_flow` 已就绪。新增引用表不自动回填历史数据；回填只能使用可证明的精确业务关联。
- Agent：现有会话/消息/实时事件表已就绪。种子依赖 [客服意图基础迁移](../customerServiceAgent/stage3_customer_service_intent_mysql_migration.sql) 中的两个表；发布继续使用现有 [意图库发布结构](../customerServiceAgent/stage3_customer_service_intent_release_mysql_migration.sql)。
- Money：核心脚本包含 `CREATE DATABASE IF NOT EXISTS db_money_agent`；账号需具备相应建库/建表权限，或先由环境管理员建立该库。

## 3. 表分工

| 数据库 | 表 |
| --- | --- |
| db_money_agent | t_money_case、t_money_task、t_money_request、t_money_grant、t_money_task_access、t_money_task_input、t_money_snapshot、t_money_report、t_money_projection、t_money_input_request、t_money_event、t_money_tool_call、t_money_action_proposal、t_money_source_inbox、t_money_outbox |
| db_agent | t_agent_money_binding、t_agent_money_dispatch、t_agent_money_delivery、t_agent_money_card、t_agent_money_notice_inbox、t_agent_money_staff_note |
| db_order | t_money_channel_observation、t_money_domain_outbox；M5 增加 t_money_repair_command |
| db_user | t_fund_business_ref、t_money_domain_outbox |

Order/User 中同名的 `t_money_domain_outbox` 是各自数据库的独立表，用于与各自业务变更在本地事务中一起提交。调用方授权、受众过滤、每案最多一个非终态任务、租约 fencing 和乐观锁递增仍由应用实现，建表本身不提供这些完整行为。

## 4. 重放与兼容

建表使用 `CREATE TABLE IF NOT EXISTS`；种子主键冲突时不覆盖已有记录。因此重复执行不会清空已有数据，但 **IF NOT EXISTS 不验证同名表的字段、默认值或索引是否一致**。首次执行及重放都应对照脚本与 `SHOW CREATE TABLE`/只读核对结果检查结构。

脚本不删除表，不批量修改既有支付/退款/账务记录，不建立跨服务外键。新表的 `created_at/updated_at/observed_at` 等时间由应用显式写入 UTC；计数器、状态、JSON 和预算字段按接口约定初始化。既有表的本地时间转换规则保持设计分册约定。

现有业务表索引需要根据实际查询与 EXPLAIN 决定。当前支付尝试、退款已有部分订单/尝试索引，本目录没有盲目叠加设计中的所有候选索引。`money_error_verify.sql` 提供现有索引清单查询；实施查询接口后若确需新增索引，再增加专门的增量迁移。

MySQL DDL 会隐式提交，不能依靠一个外层事务回滚整个建表批次。业务回滚优先关闭功能入口、保留任务及审计数据；本目录不提供删除调查记录的回滚脚本。

## 5. 意图种子与发布

新增意图为 PAYMENT_ANOMALY、REFUND_ANOMALY、MONEY_CASE_PROGRESS，路由均为 MONEY_BRIDGE。对应示例使用 `MONEY-` 前缀，意图与示例状态均为 DISABLED，embedding_status 为 PENDING；不会因运行 SQL 自动进入在线路由。

当前意图表仅有 required_slots_json，可选槽位由 IntentCatalog/CustomerSlotRegistry 实现。示例是训练种子，不能作为独立验收集。完成客服代码、权限与回复校验、端到端测试后，再启用所需定义和示例，通过 IntentReleaseService 创建、验证和激活发布快照。没有新增 PG 表或伪造向量数据。

种子不覆盖同编码已有记录；已有定义若版本或路由不同，示例插入可能被跳过，应先核对语义。首次全量导入预期 3 个定义、9 个示例，哈希核对错误数为 0。

## 6. 验证状态

已做文件级静态检查：表/列与设计对应、索引引用列、重复标识符、InnoDB 索引长度上限、种子数量与文本归一化、文档路径。尚未进行 MySQL 引擎执行、重放或并发集成验证；当前环境未提供 mysql/mysqld 或 Docker/Podman 命令。

实施时先在隔离 MySQL 8 测试库运行对应脚本并重复执行，确认已有数据不变，再运行只读核对。只读脚本中 M5 未实施时可保留该表的 MISSING；对应已实施阶段不能有缺失表/唯一键。该核对不替代实际应用权限、事务、恢复与并发测试。
