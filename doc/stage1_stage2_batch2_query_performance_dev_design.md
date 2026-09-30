# 第二批开发设计：查询与性能

| 属性 | 内容 |
| --- | --- |
| 版本 | v1.0 |
| 日期 | 2026-09-30 |
| 状态 | 待评审 |
| 对应批次 | 第二批：查询与性能 |
| 涉及模块 | `mall-order-service`、`mall-item-service`、`mall-user-service`、`mall-risk-service`、`mall-frontend` |
| 前置文档 | `stage1_stage2_optimization_review.md`、`stage1_stage2_batch1_security_consistency_dev_design.md` |

本文是开发设计文档，只描述方案和实施要求，不直接修改业务代码。

## 1. 背景与目标

当前主要性能问题：

1. 订单、资金流水、商品、商家、提现、售后等核心列表缺少分页、筛选和总数。
2. 卡密导入循环逐条插入，卡密锁定循环逐条更新。
3. 风控指标在每次评估时实时扫描 `t_risk_event`。
4. 关系图谱按节点逐个查询边，存在 N+1。
5. 缺少慢查询和风控评估耗时的统一监控。

本批目标：

- 所有核心列表接口有统一分页 DTO、筛选条件和总数。
- 列表查询能命中合适索引，默认分页参数有上限。
- 卡密导入和锁定批量化，数据库往返次数不再随数量线性放大。
- 落地 `t_risk_indicator` 指标物化，降低单次风控评估查询次数。
- 关系图谱按层级批量查询边。
- 建立慢 SQL、评估耗时和查询次数监控。

非目标：

- 不在本批引入图数据库。
- 不改变业务状态机和资金语义。
- 不做全量数据迁移，只做必要的索引和指标表初始化。

## 2. 统一分页设计

### 2.1 分页模型

在公共 API 模块或各服务公共包中统一定义：

```java
public record PageQuery(
        Integer page,
        Integer pageSize
) {
}

public record PageResult<T>(
        List<T> records,
        Integer page,
        Integer pageSize,
        Long total,
        Boolean hasMore
) {
}
```

默认值和上限：

| 参数 | 默认值 | 最小值 | 最大值 |
| --- | --- | --- | --- |
| `page` | 1 | 1 | 无上限，但深分页走游标优化 |
| `pageSize` | 20 | 1 | 100 |

兼容要求：

1. 现有无分页接口保持路径不变。
2. 未传分页参数时返回第一页 20 条，避免前端一次性依赖 100 条。
3. 响应体仍放在现有 `{code, msg, data}` 的 `data` 中。
4. 前端分页组件统一读取 `PageResult`。

### 2.2 深分页优化

管理端常规筛选使用 `page + pageSize + total`。对时间序流水和大列表，如果深分页 P95 超过目标，则增加游标模式：

```java
public record CursorQuery(
        String cursor,
        Integer pageSize
) {
}
```

游标字段：

| 数据类型 | cursor 组成 |
| --- | --- |
| 订单 | `id` |
| 资金流水 | `id` |
| 风控案件 | `updated_time + id` |
| 提现申请 | `created_time + id` |
| 商品审核 | `updated_time + id` |

游标查询使用 `WHERE ... AND (sort_field, id) < (:cursorSort, :cursorId)`，避免大 `OFFSET`。

### 2.3 通用筛选模型

列表接口统一支持：

| 字段 | 类型 | 适用列表 |
| --- | --- | --- |
| `status` | string | 订单、商家、商品、提现、售后、风控案件 |
| `scene` | string | 风控案件、风控事件 |
| `riskLevel` | string | 风控案件 |
| `keyword` | string | 单号、名称、账号等有限字段 |
| `userId` | long | 管理端订单、资金、风控案件 |
| `merchantId` | long | 管理端商品、订单、提现、风控案件 |
| `fromTime` | datetime | 订单、流水、提现、案件 |
| `toTime` | datetime | 订单、流水、提现、案件 |

约束：

1. `keyword` 最长 64，只允许后端明确字段 `LIKE` 前缀匹配。
2. 时间范围最长 92 天。
3. 用户和管理员分别限制可查询范围。
4. 所有筛选参数做白名单校验，非法值返回 400。

## 3. 分页接口设计

### 3.1 订单列表

```http
GET /api/trade/orders
```

参数：

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `page` | 否 | 默认 1 |
| `pageSize` | 否 | 默认 20 |
| `status` | 否 | 订单状态 |
| `disputeStatus` | 否 | 售后状态 |
| `fromTime` | 否 | 创建开始时间 |
| `toTime` | 否 | 创建结束时间 |

普通用户只能查自己的订单；管理员可追加 `userId`、`merchantId` 查询。

### 3.2 资金流水

```http
GET /api/account/flows
```

参数：

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `page` | 否 | 默认 1 |
| `pageSize` | 否 | 默认 20 |
| `accountType` | 否 | `AVAILABLE`、`PENDING_SETTLE`、`FROZEN` 等 |
| `fromTime` | 否 | 开始时间 |
| `toTime` | 否 | 结束时间 |

响应不返回内部备注中的敏感信息，如需审计则在管理端单独授权查看。

### 3.3 商品列表

#### 我的商品

```http
GET /api/asset/item/list
```

参数：

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `page` | 否 | 默认 1 |
| `pageSize` | 否 | 默认 20 |
| `auditStatus` | 否 | 审核状态 |
| `assetType` | 否 | 资产类型 |
| `keyword` | 否 | 商品名 |

#### 待审核商品

```http
GET /api/asset/admin/item/pending
```

管理员参数：

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `page` | 否 | 默认 1 |
| `pageSize` | 否 | 默认 20 |
| `merchantId` | 否 | 商家 ID |
| `assetType` | 否 | 资产类型 |
| `fromTime` | 否 | 提交时间 |
| `toTime` | 否 | 提交时间 |

### 3.4 商家列表

```http
GET /api/merchant/admin/list
```

参数：

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `page` | 否 | 默认 1 |
| `pageSize` | 否 | 默认 20 |
| `status` | 否 | 商家状态 |
| `userId` | 否 | 用户 ID |
| `keyword` | 否 | 商家名 |

### 3.5 提现列表

#### 用户提现

```http
GET /api/withdraw/list
```

参数：`page`、`pageSize`、`status`、`fromTime`、`toTime`。

#### 管理端待审核

```http
GET /api/withdraw/admin/pending
```

参数：`page`、`pageSize`、`merchantId`、`userId`、`fromTime`、`toTime`。

### 3.6 售后列表

```http
GET /api/trade/admin/disputes/pending
```

参数：

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `page` | 否 | 默认 1 |
| `pageSize` | 否 | 默认 20 |
| `status` | 否 | 售后状态 |
| `orderNo` | 否 | 订单号 |
| `fromTime` | 否 | 创建时间 |
| `toTime` | 否 | 创建时间 |

### 3.7 风控案件列表

```http
GET /api/admin/risk/cases
```

参数：

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `page` | 否 | 默认 1 |
| `pageSize` | 否 | 默认 20 |
| `status` | 否 | 案件状态 |
| `scene` | 否 | 风控场景 |
| `riskLevel` | 否 | 风险等级 |
| `commandStatus` | 否 | 命令状态 |
| `subjectType` | 否 | 主体类型 |
| `subjectId` | 否 | 主体 ID |
| `bizNo` | 否 | 业务单号 |
| `fromTime` | 否 | 创建时间 |
| `toTime` | 否 | 创建时间 |

响应必须返回 `total`，用于第三批风控工作台分页。

## 4. 分页 SQL 与索引设计

### 4.1 订单

现有索引：

- `idx_buyer_status(buyer_id, order_status)`
- `idx_seller_status(seller_id, order_status)`

新增或调整：

```sql
ALTER TABLE `t_trade_order`
    ADD KEY `idx_buyer_created` (`buyer_id`, `created_time`, `id`),
    ADD KEY `idx_seller_created` (`seller_id`, `created_time`, `id`),
    ADD KEY `idx_status_created` (`order_status`, `created_time`, `id`);
```

查询原则：

1. 用户维度必须带 `buyer_id` 或 `seller_id`。
2. 排序固定 `id DESC` 或 `created_time DESC, id DESC`。
3. 状态筛选放在用户维度之后。

### 4.2 资金流水

现有索引：

- `idx_owner(owner_type, owner_id, account_type)`
- `idx_created_time(created_time)`

新增：

```sql
ALTER TABLE `t_fund_flow`
    ADD KEY `idx_owner_id` (`owner_type`, `owner_id`, `id`);
```

查询固定：

```sql
WHERE owner_type = ? AND owner_id = ?
ORDER BY id DESC
LIMIT ?
```

### 4.3 商品

建议索引：

```sql
ALTER TABLE `t_item`
    ADD KEY `idx_merchant_audit_updated` (`merchant_id`, `audit_status`, `updated_time`, `id`),
    ADD KEY `idx_audit_updated` (`audit_status`, `updated_time`, `id`);
```

列表响应不返回 `detailHtml` 大字段，详情页单独查询。

### 4.4 商家

建议索引：

```sql
ALTER TABLE `t_merchant`
    ADD KEY `idx_status_updated` (`status`, `updated_time`, `id`),
    ADD KEY `idx_user_id` (`user_id`);
```

### 4.5 提现

现有索引：

- `idx_user_id(user_id)`
- `idx_status_time(status, created_time)`

新增：

```sql
ALTER TABLE `t_withdraw_request`
    ADD KEY `idx_user_status_created` (`user_id`, `status`, `created_time`, `id`),
    ADD KEY `idx_status_created_id` (`status`, `created_time`, `id`);
```

### 4.6 风控案件

现有索引：

- `idx_status_scene_time(status, scene, created_time)`
- `idx_biz(biz_type, biz_no)`
- `idx_assigned(assigned_to, status)`

新增：

```sql
ALTER TABLE `t_risk_case`
    ADD KEY `idx_status_level_updated` (`status`, `risk_level`, `updated_time`, `id`),
    ADD KEY `idx_command_status_updated` (`command_status`, `updated_time`, `id`),
    ADD KEY `idx_subject` (`subject_type`, `subject_id`, `updated_time`);
```

## 5. 卡密导入批量化设计

### 5.1 当前问题

`AssetListingService.importCards()` 当前在循环中逐条插入 `CardSecret`。导入 1000 张卡密会产生 1000 次 insert。

### 5.2 目标流程

```text
1. 参数校验和数量上限校验
2. 去空、trim、去重
3. 生成 cipher、hash、mask
4. 批量查询已存在的 secret_hash
5. 如存在冲突，事务回滚并返回明确错误
6. 分批 INSERT
7. 按实际插入数量更新商品库存
8. 写入导入审计
```

### 5.3 批量插入

Mapper 使用动态批量插入：

```sql
INSERT INTO t_card_secret(
    item_id, merchant_id, secret_cipher, secret_hash, secret_mask,
    status, created_time, updated_time
) VALUES
    (#{item.id}, #{item.merchantId}, #{card.cipher}, #{card.hash}, #{card.mask},
     'AVAILABLE', #{now}, #{now}),
    ...
```

分批策略：

| 配置 | 默认值 |
| --- | --- |
| 单次导入上限 | 1000 |
| 单批插入数量 | 200 |
| 超过单批上限 | 拆分批次 |

冲突检测：

1. 插入前按 `item_id + secret_hash IN (...)` 查询。
2. 发现已存在 hash 时直接抛业务异常。
3. 不向用户返回卡密明文，只返回序号或数量。

### 5.4 库存更新

```sql
UPDATE t_item
SET stock = stock + #{insertedCount}
WHERE id = #{itemId}
```

要求：

1. `insertedCount` 必须等于成功插入行数。
2. 插入和库存更新在同一个本地事务内。
3. 批次之间如果失败，整个导入回滚，不允许部分成功。
4. 导入成功后写入操作审计，包含 `itemId`、导入数量、操作者、traceId。

## 6. 卡密锁定批量化设计

### 6.1 当前问题

`AssetService.reserve()` 当前先查询卡密，再逐条 `lockById()`。并发下单时数据库往返次数随数量线性增长。

### 6.2 目标 SQL

#### 6.2.1 锁定候选卡密

```sql
SELECT id
FROM t_card_secret
WHERE item_id = #{itemId}
  AND status = 'AVAILABLE'
ORDER BY id
LIMIT #{quantity}
FOR UPDATE SKIP LOCKED;
```

说明：

- `ORDER BY id` 保证锁定顺序稳定，降低死锁概率。
- `SKIP LOCKED` 避免并发事务相互等待。
- MySQL 8 支持该语法，项目当前使用 MySQL 8.0 驱动和数据库版本需在联调环境确认。

#### 6.2.2 批量更新卡密

```sql
UPDATE t_card_secret
SET status = 'LOCKED',
    order_no = #{orderNo},
    reservation_no = #{reservationNo},
    locked_time = #{now},
    updated_time = #{now}
WHERE id IN (...)
  AND status = 'AVAILABLE';
```

校验：

1. 候选数量必须等于 `quantity`，否则返回库存不足。
2. 批量更新影响行数必须等于 `quantity`。
3. 更新失败时抛异常并回滚。

#### 6.2.3 扣减商品库存

```sql
UPDATE t_item
SET stock = stock - #{quantity}
WHERE id = #{itemId}
  AND stock >= #{quantity};
```

如果影响行数为 0，返回库存不足并回滚。

### 6.3 事务边界

```text
BEGIN
1. 查询并锁定候选卡密
2. 批量更新卡密为 LOCKED
3. 原子扣减商品库存
4. 插入资产预留单
COMMIT
```

此事务只包含本地 SQL，不包含远程调用。

### 6.4 并发要求

1. 同一订单重复预留由 `uk_order_no` 或幂等键拒绝。
2. 不同订单并发锁定同一商品时不超卖。
3. 事务内不允许调用风控或资金服务。
4. 锁定失败必须回滚全部卡密更新。
5. 补偿释放同样使用 `order_no + reservation_no` 批量更新。

## 7. 风控指标物化设计

### 7.1 现状

`RiskMetricService.calculate()` 每次评估实时查询：

- 用户订单数、完成数、争议数、设备数、IP 数。
- 商家完成数、退款数、争议数、提现金额、提现账号关联数。
- 买卖双方关联关系。

阶段二 Schema 已有 `t_risk_indicator`，但当前实现未使用。

### 7.2 目标架构

```text
t_risk_event
    |
    | 确认事件后投递刷新任务
    v
RiskIndicatorRefreshJob / MQ Consumer
    |
    | 按主体和窗口批量重算
    v
t_risk_indicator
    |
    | 指标读取服务
    v
RiskMetricService
    |
    | 请求级指标 + 物化指标
    v
规则评估
```

### 7.3 指标范围

| 指标 | 主体 | 窗口 |
| --- | --- | --- |
| `USER_ORDER_COUNT` | USER | `10M`、`24H` |
| `USER_COMPLETED_COUNT` | USER | `TOTAL` |
| `USER_DISPUTE_COUNT` | USER | `7D` |
| `USER_DEVICE_COUNT` | USER | `30D` |
| `USER_IP_COUNT` | USER | `30D` |
| `MERCHANT_COMPLETED_COUNT` | MERCHANT | `TOTAL` |
| `MERCHANT_REFUND_COUNT` | MERCHANT | `30D` |
| `MERCHANT_DISPUTE_COUNT` | MERCHANT | `30D` |
| `MERCHANT_WITHDRAW_AMOUNT` | MERCHANT | `24H` |
| `REGISTER_SAME_IP_COUNT` | IP | `10M` |
| `REGISTER_SAME_DEVICE_COUNT` | DEVICE | `30D` |
| `WITHDRAW_ACCOUNT_MERCHANT_COUNT` | ACCOUNT_HASH | `180D` |

请求级指标仍实时计算：

- `CURRENT_AMOUNT`
- `USER_AGE_HOURS`
- `MERCHANT_AGE_HOURS`
- `ORDER_PAY_TO_DELIVER_SECONDS`
- `ORDER_VIEW_TO_CONFIRM_SECONDS`
- `ITEM_PRICE_DEVIATION`
- `SENSITIVE_WORD_CATEGORY`

### 7.4 刷新任务表

新增刷新任务表，保证刷新可恢复：

```sql
CREATE TABLE IF NOT EXISTS `t_risk_indicator_refresh_task` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `subject_type` varchar(16) NOT NULL,
    `subject_id` bigint unsigned NOT NULL,
    `metric_codes_json` json DEFAULT NULL,
    `status` varchar(16) NOT NULL DEFAULT 'PENDING',
    `retry_count` int NOT NULL DEFAULT 0,
    `max_retry_count` int NOT NULL DEFAULT 3,
    `next_execute_time` datetime(3) NOT NULL,
    `last_error` varchar(512) DEFAULT NULL,
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_subject_pending` (`subject_type`, `subject_id`, `status`),
    KEY `idx_status_next` (`status`, `next_execute_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='风控指标刷新任务表';
```

任务状态：

| 状态 | 说明 |
| --- | --- |
| `PENDING` | 等待刷新 |
| `RUNNING` | 刷新中 |
| `SUCCESS` | 已完成 |
| `FAILED` | 可重试失败 |
| `MANUAL_PENDING` | 超过重试上限 |

写入规则：

1. 风控事件确认后，解析受影响主体。
2. 对每个主体插入或合并刷新任务。
3. 同一主体短时间重复触发时只保留一个 `PENDING` 任务。
4. 任务执行完成后删除或标记 `SUCCESS`，保留最近一段时间的执行记录用于排障。

### 7.5 计算策略

刷新任务按主体执行少量聚合查询，并写入 `t_risk_indicator`。

示例：

```sql
SELECT
    COUNT(*) AS order_count,
    SUM(CASE WHEN event_type = 'CONFIRM' THEN 1 ELSE 0 END) AS confirm_count,
    SUM(CASE WHEN event_type = 'OPEN' THEN 1 ELSE 0 END) AS dispute_count
FROM t_risk_event
WHERE user_id = ?
  AND event_phase = 'CONFIRMED'
  AND occurred_time >= ?
  AND occurred_time < ?;
```

要求：

1. 每个主体一次刷新最多执行 5 条聚合 SQL。
2. 聚合查询必须使用现有 `idx_user_phase_time`、`idx_merchant_phase_time` 等索引。
3. 写入 `t_risk_indicator` 使用 upsert。
4. `window_start` 和 `window_end` 由计算时间确定。
5. 计算结果保留 4 位小数。

### 7.6 新鲜度策略

| 窗口 | 最大允许延迟 | 缓存 TTL |
| --- | --- | --- |
| `10M` | 10 秒 | 5 秒 |
| `24H` | 60 秒 | 30 秒 |
| `7D` | 5 分钟 | 60 秒 |
| `30D` | 10 分钟 | 5 分钟 |
| `TOTAL` | 5 分钟 | 60 秒 |

读取策略：

1. 优先读 `t_risk_indicator`。
2. 如果指标存在且未超过新鲜度阈值，直接使用。
3. 如果指标过期，先返回旧值并投递刷新任务。
4. 如果指标不存在，允许同步计算一次并写入，同时记录降级日志。
5. 指标缺失不能导致规则误放行，必须在决策 `missingMetrics` 中记录。

### 7.7 评估查询预算

新增评估查询计数组件：

- `risk_evaluate_db_query_count`
- `risk_evaluate_duration_seconds`
- `risk_indicator_cache_hit_total`
- `risk_indicator_cache_miss_total`
- `risk_indicator_refresh_duration_seconds`

目标：

| 指标 | 目标 |
| --- | --- |
| 单次评估数据库查询次数 | 不超过 3 |
| 指标命中率 | 大于 95% |
| 单次评估 P95 | 小于 100ms |
| 刷新任务 P95 | 小于 500ms |

超过查询预算时：

1. 记录 warn 日志。
2. 输出 traceId、scene、eventNo、metricCode。
3. 触发指标治理告警。
4. 不中断当前评估，除非资金安全必需指标缺失。

## 8. 关系图谱查询优化

### 8.1 当前问题

`RiskIdentityService.getRelations()` 在循环中逐个节点查询 `t_relation_edge`。当 frontier 节点较多时，数据库查询次数线性增长。

### 8.2 批量查询接口

新增 Mapper 方法：

```java
List<RelationEdge> selectByUserIds(@Param("userIds") Collection<Long> userIds);
```

SQL：

```sql
SELECT *
FROM t_relation_edge
WHERE source_user_id IN (...)
   OR target_user_id IN (...)
ORDER BY last_seen_time DESC, id DESC
LIMIT 600;
```

要求：

1. 每层只执行一次边查询。
2. depth 固定最大 2。
3. 节点上限 100，边上限 300。
4. 如果数据库返回 600 条，标记结果截断。

### 8.3 买卖关系查询

`buyerSellerRelation()` 改为精确 exists 查询：

```sql
SELECT EXISTS(
    SELECT 1
    FROM t_user_device
    WHERE user_id = #{buyerId}
      AND device_hash = #{deviceHash}
)
```

IP 查询同理。

判断优先级：

1. 同用户。
2. 同设备。
3. 同 IP。
4. 历史交易关系。

只返回枚举值，不返回设备和 IP 原始哈希给前端。

## 9. 监控设计

### 9.1 数据库监控

开启或统一配置：

1. 慢 SQL 阈值：500ms。
2. SQL 执行耗时 P95。
3. 全表扫描次数。
4. 索引未命中告警。
5. 连接池等待时间。

### 9.2 业务监控

| 指标 | 说明 | 告警阈值 |
| --- | --- | --- |
| `trade_order_list_duration_seconds` | 订单列表耗时 | P95 > 300ms |
| `fund_flow_list_duration_seconds` | 资金流水列表耗时 | P95 > 300ms |
| `card_import_duration_seconds` | 卡密导入耗时 | 1000 条 > 3s |
| `card_reserve_duration_seconds` | 卡密锁定耗时 | P95 > 100ms |
| `risk_evaluate_duration_seconds` | 风控评估耗时 | P95 > 100ms |
| `risk_evaluate_db_query_count` | 评估查询数 | > 3 |
| `risk_indicator_refresh_lag_seconds` | 指标延迟 | > 60s |
| `relation_graph_duration_seconds` | 图谱查询耗时 | P95 > 300ms |

### 9.3 日志字段

关键日志统一带：

- `traceId`
- `userId`
- `merchantId`
- `orderNo`
- `itemId`
- `eventNo`
- `page`
- `pageSize`
- `queryCount`
- `durationMs`

## 10. 开发任务拆分

| 任务 | 内容 | 预估规模 | 依赖 |
| --- | --- | --- | --- |
| B2-01 | 定义分页 DTO 和参数校验 | 中 | 无 |
| B2-02 | 订单、流水、售后分页 | 中 | B2-01 |
| B2-03 | 商品、商家、提现分页 | 中 | B2-01 |
| B2-04 | 风控案件分页和筛选 | 中 | B2-01 |
| B2-05 | 列表响应 DTO 与大字段裁剪 | 中 | 各列表接口 |
| B2-06 | 索引迁移和执行计划验证 | 中 | SQL 设计 |
| B2-07 | 卡密批量导入 | 中 | 无 |
| B2-08 | 卡密批量锁定 | 大 | 无 |
| B2-09 | 指标刷新任务表和实体 | 大 | 无 |
| B2-10 | 指标计算与读取服务 | 大 | B2-09 |
| B2-11 | RiskMetricService 接入物化指标 | 大 | B2-10 |
| B2-12 | 关系图谱批量查询 | 中 | 无 |
| B2-13 | Micrometer 指标与慢 SQL 监控 | 中 | 各接口 |
| B2-14 | 分页与性能测试 | 大 | 全部 |

## 11. 测试设计

### 11.1 分页测试

1. 构造 150 条订单，验证第 8 页 20 条和总数。
2. `pageSize=0`、`pageSize=101`、`page=0` 返回 400。
3. 状态、时间、关键字筛选结果正确。
4. 时间范围超过 92 天返回 400。
5. 普通用户不能通过 `userId` 查询他人数据。
6. 列表接口响应不包含大字段。
7. 深分页执行计划不出现全表扫描。

### 11.2 卡密导入测试

1. 导入 1000 张卡密，insert 批次数不超过 5。
2. 输入重复卡密时先去重。
3. 已存在 hash 时导入失败且库存不变。
4. 批次中途失败时全部回滚。
5. 导入成功后库存增量等于实际插入数。
6. 响应不泄露卡密明文。

### 11.3 卡密锁定测试

1. 库存 100，并发 20 个订单各锁 10，成功订单数为 10。
2. 卡密不重复锁定。
3. 商品库存不为负。
4. 重复 `orderNo` 只创建一个预留单。
5. 锁定失败时卡密和库存全部回滚。
6. 单次锁定 SQL 数量固定，不随数量线性增长。

### 11.4 风控指标测试

1. 事件确认后生成对应刷新任务。
2. 刷新任务幂等，重复执行不产生错误指标。
3. 指标过期时返回旧值并投递刷新。
4. 指标缺失时记录 `missingMetrics`。
5. 单次评估数据库查询数不超过 3。
6. 相同输入下指标结果与实时计算一致。

### 11.5 图谱测试

1. depth=2 时边查询次数不超过 2。
2. 节点超过 100 时返回 truncated。
3. 边超过 300 时返回 truncated。
4. 图谱结果不包含原始 deviceHash 和 ipHash。
5. 大图查询 P95 小于 300ms。

## 12. 灰度与回滚

### 12.1 发布顺序

1. 发布索引和分页 DTO。
2. 列表接口保持默认分页兼容。
3. 前端逐块接入分页。
4. 发布卡密批量化。
5. 初始化指标表并执行历史指标回填。
6. 打开物化指标读取开关。
7. 打开图谱批量查询。

### 12.2 配置

```yaml
pagination:
  default-page-size: 20
  max-page-size: 100

asset:
  card-import:
    max-count: 1000
    batch-size: 200

risk:
  indicator:
    materialized-enabled: ${RISK_INDICATOR_MATERIALIZED_ENABLED:false}
    query-budget: 3
    refresh-batch-size: 100
```

### 12.3 回滚

- 分页接口可通过默认参数保持兼容，无需回滚前端。
- 卡密批量化异常时回退旧逐条实现，但必须保留并发测试结果。
- 物化指标异常时关闭 `materialized-enabled`，回到实时计算。
- 索引回滚需评估锁表影响，生产建议使用在线 DDL。

## 13. 验收标准

1. 所有核心列表接口返回 `records`、`page`、`pageSize`、`total`、`hasMore`。
2. 默认分页和最大分页限制生效。
3. 常用筛选条件均能命中索引。
4. 列表接口不再返回 `detailHtml` 等大字段。
5. 导入 1000 张卡密的数据库插入批次数不超过 5。
6. 并发锁定测试无超卖、无重复锁定。
7. 单次风控评估数据库查询次数不超过 3。
8. 风控指标命中率大于 95%。
9. 图谱 depth=2 查询数据库次数不超过 2。
10. 慢 SQL 和评估耗时监控上线，并有告警阈值。
