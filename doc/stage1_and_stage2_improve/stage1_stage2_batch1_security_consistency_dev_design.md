# 第一批开发设计：安全与一致性

| 属性 | 内容 |
| --- | --- |
| 版本 | v1.0 |
| 日期 | 2026-09-30 |
| 状态 | 待评审 |
| 对应批次 | 第一批：安全与一致性 |
| 涉及模块 | `mall-order-service`、`mall-item-service`、`mall-user-service`、`mall-risk-service`、`mall-gateway-service` |
| 前置文档 | `stage1_stage2_optimization_review.md` |

本文是开发设计文档，只描述目标方案、数据结构、流程、任务拆分和验收标准，不直接修改业务代码。

## 1. 背景与目标

第一批需要解决两个最高优先级问题：

1. Mock 支付回调签名可被外部推导和伪造，缺少时间戳、nonce 与防重放机制。
2. 订单本地事务内包含资产、资金、风控等远程调用，缺少统一的幂等、恢复和补偿模型。

本批目标：

- 回调签名必须依赖服务端密钥，外部无法根据公开信息构造。
- 回调必须具备时间窗口校验、nonce 去重和重放防护。
- 跨服务操作必须先落操作意图，再执行远程调用，且具备幂等键、重试和补偿定义。
- 远程调用不得长期包裹在本地数据库事务内。
- 支付、托管、结算、退款链路具备自动对账和差异告警。
- 关键安全场景和多实例并发场景有自动化测试。

非目标：

- 不在本批引入完整 Seata 全局事务，避免把一致性问题隐藏在长全局事务里。
- 不重构所有订单服务职责，服务拆分放到第四批。
- 不接真实微信或支付宝渠道，只定义能平滑迁移到真实渠道的回调协议。

## 2. 总体架构

### 2.1 目标链路

```text
支付渠道 / Mock 支付
    |
    | HTTPS + HMAC-SHA256 + timestamp + nonce
    v
Spring Cloud Gateway
    |
    | 保留公开路径，但增加来源与限流控制
    v
mall-order-service
    |
    | 1. 校验签名、时间窗口、nonce
    | 2. 短事务保存回调与编排任务
    v
TradeOrchestrationService
    |
    | 事务提交后驱动 / 定时恢复
    +--> AssetDubboService
    +--> FundDubboService
    +--> MerchantDubboService
    +--> RiskClient
    |
    | 每一步记录幂等键、执行结果和补偿状态
    v
订单 / 资产 / 资金 / 风控最终一致
```

### 2.2 设计原则

1. **先验证，再落库，再编排**：无效回调不进入业务编排。
2. **本地短事务**：数据库事务只负责本地状态和任务落库。
3. **显式状态**：跨服务过程使用可查询的中间态，不用异常和日志推断业务进度。
4. **幂等优先**：所有远程操作以业务单号和操作类型生成幂等键。
5. **可恢复优先**：每个失败步骤都有重试、补偿或人工处理路径。
6. **可审计**：签名材料、任务步骤、对账差异都保留证据。

## 3. 支付回调安全设计

### 3.1 回调请求协议

新增回调 DTO，替换 Controller 中的 `Map<String, Object>` 入参。

```java
public record PaymentCallbackRequest(
        String callbackNo,
        String paymentNo,
        String orderNo,
        BigDecimal amount,
        String result,
        Long timestamp,
        String nonce,
        String signature,
        String rawPayload
) {
}
```

字段约束：

| 字段 | 必填 | 约束 | 说明 |
| --- | --- | --- | --- |
| `callbackNo` | 是 | 最长 64，渠道唯一 | 渠道回调号，用于回调记录去重 |
| `paymentNo` | 是 | 最长 64 | 平台支付单号 |
| `orderNo` | 是 | 最长 64 | 平台订单号 |
| `amount` | 是 | 大于 0，最多 2 位小数 | 支付金额 |
| `result` | 是 | `SUCCESS` / `FAIL` | 支付结果 |
| `timestamp` | 是 | epoch millis | 请求时间戳 |
| `nonce` | 是 | 16 到 64 位随机字符串 | 防重放随机数 |
| `signature` | 是 | 128 位十六进制 | HMAC-SHA256 结果 |
| `rawPayload` | 是 | 原始报文，长度受限 | 审计证据 |

### 3.2 签名算法

统一使用 HMAC-SHA256。

```text
canonicalText =
    paymentNo + "\n" +
    orderNo + "\n" +
    amount + "\n" +
    result + "\n" +
    timestamp + "\n" +
    nonce + "\n" +
    sha256(rawPayload)

signature =
    hex(HMAC-SHA256(callbackSecret, canonicalText))
```

实现要求：

- `amount` 参与签名时使用统一的规范化字符串，例如保留两位小数：`100.00`。
- 签名比较使用常量时间比较，避免 timing attack。
- `rawPayload` 先做 SHA-256，再参与 HMAC 拼装，避免超长报文影响签名逻辑。
- 不再把 `callbackTokenHash` 作为唯一校验依据，可以保留字段用于灰度回滚。

### 3.3 配置设计

配置跟随现有 Nacos 配置风格，使用环境变量占位符，不在代码和文档中固化生产密钥。

```yaml
trade:
  payment-callback:
    secret: ${PAYMENT_CALLBACK_SECRET}
    secret-version: ${PAYMENT_CALLBACK_SECRET_VERSION:1}
    clock-skew-seconds: ${PAYMENT_CALLBACK_CLOCK_SKEW_SECONDS:300}
    nonce-ttl-seconds: ${PAYMENT_CALLBACK_NONCE_TTL_SECONDS:900}
    mock-enabled: ${PAYMENT_MOCK_ENABLED:false}
```

密钥管理要求：

1. 生产密钥只保存在 Nacos 加密配置或密钥管理系统。
2. 支持 `secret-version`，便于轮换。
3. 新旧密钥并行窗口内，先验新密钥，再验旧密钥；并行窗口结束后只允许新密钥。
4. 任何日志不得输出密钥、完整签名原文和 `rawPayload` 明文。
5. Mock 支付接口只在 `local`、`dev`、`test` 环境开启。

### 3.4 校验顺序

回调校验必须按以下顺序执行：

1. DTO 参数完整性校验。
2. 支付单存在性校验。
3. `paymentNo` 与 `orderNo` 匹配校验。
4. 时间窗口校验。
5. nonce 未使用校验。
6. HMAC 签名校验。
7. 金额与订单金额校验。
8. 支付单和订单状态机校验。
9. 保存回调记录和 nonce。
10. 创建支付确认编排任务。

说明：

- nonce 检查放在签名校验前，可以尽早拒绝明显重放的请求。
- 即使签名失败，也要记录失败事件，便于安全监控。
- 所有拒绝原因必须落结构化日志，并输出稳定错误码。

### 3.5 数据库设计

#### 3.5.1 扩展回调表

```sql
ALTER TABLE `t_payment_callback`
    ADD COLUMN `timestamp_epoch_ms` bigint NOT NULL DEFAULT 0 COMMENT '回调时间戳',
    ADD COLUMN `nonce` varchar(64) NOT NULL DEFAULT '' COMMENT '回调随机数',
    ADD COLUMN `signature_algorithm` varchar(32) NOT NULL DEFAULT 'HMAC-SHA256' COMMENT '签名算法',
    ADD COLUMN `secret_version` int NOT NULL DEFAULT 1 COMMENT '密钥版本',
    ADD COLUMN `verify_status` varchar(24) NOT NULL DEFAULT 'PENDING' COMMENT 'VERIFY_PASSED, VERIFY_FAILED',
    ADD COLUMN `failure_reason` varchar(128) DEFAULT NULL COMMENT '校验失败原因',
    ADD UNIQUE KEY `uk_payment_nonce` (`payment_no`, `nonce`);
```

状态：

| 状态 | 说明 |
| --- | --- |
| `PENDING` | 已接收，等待校验 |
| `VERIFY_PASSED` | 校验通过，进入编排 |
| `VERIFY_FAILED` | 校验失败，不推进业务 |

#### 3.5.2 新增 nonce 表

虽然回调表可以增加唯一键，但为了支持 TTL 清理和全局防重放，建议单独维护 nonce 表。

```sql
CREATE TABLE IF NOT EXISTS `t_payment_callback_nonce` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `nonce` varchar(64) NOT NULL,
    `payment_no` varchar(64) DEFAULT NULL,
    `secret_version` int NOT NULL DEFAULT 1,
    `expire_time` datetime(3) NOT NULL,
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_nonce` (`nonce`),
    KEY `idx_expire_time` (`expire_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='支付回调防重放表';
```

写入规则：

1. 使用 `INSERT IGNORE` 或捕获 `DuplicateKeyException`。
2. 插入成功才继续处理。
3. 插入冲突时返回 `NONCE_DUPLICATED`。
4. 定时任务删除 `expire_time < now - 1 day` 的记录。

#### 3.5.3 支付单表调整

```sql
ALTER TABLE `t_payment_order`
    ADD COLUMN `callback_secret_version` int NOT NULL DEFAULT 1 COMMENT '创建支付单时密钥版本';
```

保留 `callback_token_hash`，用于灰度期间兼容旧回调；新协议上线稳定后再标记废弃。

### 3.6 错误码与响应

| 错误码 | HTTP 状态 | 说明 |
| --- | --- | --- |
| `PAY_CALLBACK_PARAM_INVALID` | 400 | 参数缺失或格式错误 |
| `PAY_CALLBACK_NOT_FOUND` | 404 | 支付单不存在 |
| `PAY_CALLBACK_ORDER_MISMATCH` | 400 | 支付单与订单不匹配 |
| `PAY_CALLBACK_TIMESTAMP_EXPIRED` | 401 | 时间戳超出允许窗口 |
| `PAY_CALLBACK_NONCE_DUPLICATED` | 409 | nonce 已使用 |
| `PAY_CALLBACK_SIGNATURE_INVALID` | 401 | 签名错误 |
| `PAY_CALLBACK_AMOUNT_MISMATCH` | 400 | 金额不匹配 |
| `PAY_CALLBACK_STATE_INVALID` | 409 | 支付单或订单状态不允许处理 |
| `PAY_CALLBACK_ACCEPTED` | 202 | 校验通过，进入异步确认 |
| `PAY_CALLBACK_DUPLICATED` | 200 | 已处理过，返回幂等成功 |

渠道回调响应必须稳定。对已经成功处理的重复通知，返回成功，避免渠道无限重试。

### 3.7 网关与安全控制

1. `POST:/api/payment/callback` 继续保持公开路径，因为支付渠道不带平台 JWT。
2. 增加专用限流规则：
   - key：来源 IP + `paymentNo`。
   - 建议 replenish rate：1 QPS，burst 10。
3. 生产环境配置回调来源 allowlist，或要求渠道走固定专线域名。
4. 网关透传 `X-Trace-Id`、`X-Forwarded-For`。
5. 签名失败次数、nonce 冲突次数、时间窗口失败次数接入监控告警。

## 4. 跨服务一致性设计

### 4.1 总体模型

使用“操作意图 + 步骤表 + 事务后消息 + 定时恢复”的轻量 Saga 模型。

核心思想：

1. 订单服务在本地短事务中创建编排任务和步骤。
2. 事务提交后发送 RocketMQ 消息触发执行。
3. 如果消息丢失或执行中断，XXL-Job 扫描任务恢复。
4. 每一步都有唯一幂等键，下游重复执行不产生副作用。
5. 失败步骤按策略重试、补偿或转人工。

### 4.2 编排任务表

```sql
CREATE TABLE IF NOT EXISTS `t_trade_orchestration_task` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `task_no` varchar(64) NOT NULL,
    `task_type` varchar(64) NOT NULL,
    `biz_type` varchar(32) NOT NULL,
    `biz_no` varchar(64) NOT NULL,
    `status` varchar(24) NOT NULL DEFAULT 'INIT',
    `context_json` json NOT NULL,
    `idempotency_key` varchar(128) NOT NULL,
    `retry_count` int NOT NULL DEFAULT 0,
    `max_retry_count` int NOT NULL DEFAULT 5,
    `next_execute_time` datetime(3) DEFAULT NULL,
    `locked_by` varchar(64) DEFAULT NULL,
    `locked_until` datetime(3) DEFAULT NULL,
    `trace_id` varchar(64) DEFAULT NULL,
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_task_no` (`task_no`),
    UNIQUE KEY `uk_task_idempotency` (`idempotency_key`),
    KEY `idx_status_next_time` (`status`, `next_execute_time`),
    KEY `idx_biz` (`biz_type`, `biz_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='担保交易编排任务表';
```

任务状态：

| 状态 | 说明 |
| --- | --- |
| `INIT` | 已创建，等待执行 |
| `RUNNING` | 正在执行 |
| `SUCCESS` | 全部步骤成功 |
| `FAILED` | 可重试失败 |
| `COMPENSATING` | 补偿执行中 |
| `COMPENSATED` | 已回滚或业务取消 |
| `MANUAL_PENDING` | 超过重试上限，等待人工 |

### 4.3 编排步骤表

```sql
CREATE TABLE IF NOT EXISTS `t_trade_orchestration_step` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `task_no` varchar(64) NOT NULL,
    `step_no` int NOT NULL,
    `step_name` varchar(64) NOT NULL,
    `step_type` varchar(32) NOT NULL,
    `status` varchar(24) NOT NULL DEFAULT 'INIT',
    `idempotency_key` varchar(128) NOT NULL,
    `request_json` json DEFAULT NULL,
    `response_json` json DEFAULT NULL,
    `attempt_count` int NOT NULL DEFAULT 0,
    `max_attempt_count` int NOT NULL DEFAULT 5,
    `next_execute_time` datetime(3) DEFAULT NULL,
    `last_error` varchar(512) DEFAULT NULL,
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_task_step` (`task_no`, `step_no`),
    UNIQUE KEY `uk_step_idempotency` (`idempotency_key`),
    KEY `idx_task_status` (`task_no`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='担保交易编排步骤表';
```

步骤状态：

| 状态 | 说明 |
| --- | --- |
| `INIT` | 待执行 |
| `RUNNING` | 执行中 |
| `SUCCESS` | 成功 |
| `FAILED` | 可重试失败 |
| `COMPENSATING` | 补偿中 |
| `COMPENSATED` | 已补偿 |
| `SKIPPED` | 条件不满足，跳过 |
| `MANUAL_PENDING` | 人工处理 |

### 4.4 幂等键规范

幂等键统一由订单编排层生成，下游不得重新随机生成。

| 操作 | 幂等键 | 说明 |
| --- | --- | --- |
| 资产预留 | `ASSET_RESERVE:{orderNo}` | 同一订单只允许一次预留 |
| 资产确认 | `ASSET_CONFIRM:{orderNo}` | 支付成功后确认卡密占用 |
| 资产释放 | `ASSET_RELEASE:{orderNo}` | 未支付关单释放 |
| 资产作废 | `ASSET_INVALIDATE:{orderNo}` | 退款后作废资产 |
| 资金冻结 | `FUND_FREEZE:{orderNo}` | 托管资金冻结 |
| 资金结算 | `FUND_SETTLE:{orderNo}` | 平台结算入待结算余额 |
| 待结算转可用 | `FUND_SETTLE_AVAILABLE:{orderNo}` | 卖家可用余额入账 |
| 托管退款 | `FUND_REFUND:{orderNo}` | 全额退款 |
| 商家退款统计 | `MERCHANT_REFUND:{orderNo}` | 商家维度退款计数 |
| 风控预检查 | `RISK_PRECHECK:{eventNo}` | 创建、支付等预检查事件 |
| 风控事件确认 | `RISK_CONFIRM:{eventNo}` | 事件确认 |
| 风控命令 | `RISK_COMMAND:{commandNo}` | 案件命令 |

下游幂等要求：

1. 资产、资金、商家服务接收幂等键。
2. 重复请求返回上次结果，或返回当前已满足状态。
3. 不允许重复扣库存、重复冻结、重复入账、重复退款。

### 4.5 操作与补偿矩阵

| 步骤 | 目标状态 | 成功判断 | 失败处理 | 补偿动作 |
| --- | --- | --- | --- | --- |
| 资产预留 | 卡密或库存锁定 | 预留单 `RESERVED` | 订单创建失败，释放预留 | 调用资产释放 |
| 风控预检查 | 决策结果落库 | 决策 `PASS` 或非拒绝动作 | 转人工或终止创建 | 释放资产预留 |
| 资金冻结 | 平台托管增加 | 资金事务 `SUCCESS` | 重试，超过上限转人工 | 退款或人工调账 |
| 资产确认 | 卡密 `SOLD/CONFIRMED` | 资产确认成功 | 重试，超过上限转人工 | 退款并作废资产 |
| 资金结算 | 卖家待结算增加 | 结算事务成功 | 重试，超过上限转人工 | 冲正结算入账 |
| 待结算转可用 | 卖家可用增加 | 可用入账成功 | 重试，超过上限转人工 | 冲正待结算转可用 |
| 托管退款 | 买家可用增加 | 退款事务成功 | 重试，超过上限转人工 | 不自动反向，转人工 |
| 资产作废 | 卡密 `INVALID` | 作废成功 | 重试，超过上限转人工 | 不自动恢复，转人工 |
| 商家退款统计 | 商家统计更新 | 更新成功 | 异步重试 | 幂等补偿更新 |
| 风控事件确认 | 事件 `CONFIRMED` | 确认成功 | 异步重试 | 无资金影响 |

### 4.6 消息与任务驱动

新增 RocketMQ Topic：

```text
trade-orchestration-task-topic
```

消息体只包含索引字段：

```json
{
  "taskNo": "OT-ORDER-123456",
  "taskType": "PAY_CONFIRM",
  "bizNo": "TR123456",
  "traceId": "..."
}
```

执行规则：

1. 本地任务和步骤落库成功后，事务提交后发送消息。
2. 消费者按 `taskNo` 加载完整上下文，不信任消息里的业务金额。
3. 消费成功不修改任务状态，只推进步骤状态。
4. 全部步骤成功后，任务更新为 `SUCCESS`。
5. 消息发送失败时，任务保持 `INIT`，由恢复任务扫描。
6. 消费异常时消息按 RocketMQ 重试策略重试。

### 4.7 任务恢复设计

新增 XXL-Job：

```text
tradeOrchestrationRecoverJob
```

扫描规则：

1. `INIT` 且 `next_execute_time <= now`。
2. `RUNNING` 且 `locked_until < now`。
3. `FAILED` 且重试次数未超限。
4. `COMPENSATING` 且补偿步骤未完成。

执行策略：

1. 每批最多 100 个任务。
2. 使用 `locked_by + locked_until` 做数据库抢占，或复用 Redis 分布式锁。
3. 抢占成功后设置 `locked_until = now + 60s`。
4. 执行完成或失败后释放锁。
5. 服务实例宕机后锁自然过期，可被其他实例接管。

### 4.8 核心流程设计

#### 4.8.1 创建订单

目标：不在一个本地事务里连续调用资产和风控远程服务。

```text
1. 参数校验
2. 短事务：
   - 创建 TradeOrder，状态 CREATE_PENDING
   - 创建 orchestration task
   - 创建步骤：RISK_PRECHECK、ASSET_RESERVE、ORDER_READY
3. 事务提交后触发任务
4. 风控预检查
5. 资产预留
6. 短事务更新订单为 WAIT_PAY，生成支付单
7. 风控事件事务后确认
```

失败策略：

- 风控拒绝：任务终态 `COMPENSATED`，订单置为 `REJECTED`。
- 资产库存不足：订单置为 `CLOSED`，原因记录到状态日志。
- 远程超时：按步骤重试。
- 重试超限：任务转 `MANUAL_PENDING`。

#### 4.8.2 支付回调确认

```text
1. 校验回调签名、时间戳、nonce
2. 短事务：
   - 保存回调记录
   - 占用 nonce
   - 支付单状态 PAYING/SUCCESS_PENDING
   - 创建 PAY_CONFIRM 编排任务
3. 返回渠道 ACCEPTED
4. 任务执行：
   - 资金冻结
   - 资产确认
   - 本地订单更新 PAID
   - 支付单更新 SUCCESS
   - 记录状态日志
```

新增订单状态：

| 状态 | 说明 |
| --- | --- |
| `PAY_CONFIRMING` | 支付回调已通过校验，资金和资产确认中 |

如果支付渠道已成功但平台确认失败，订单不能直接关闭，必须由任务恢复或人工处理。

#### 4.8.3 订单结算

```text
1. 满足结算条件
2. 短事务：
   - 订单 SETTLING
   - 创建 SETTLE 编排任务
3. 资金结算入待结算余额
4. 本地保存结算单
5. 待结算转可用
6. 订单 SETTLED
```

要求：

- 结算单必须记录资金事务号。
- 先有资金事务号，再允许本地结算单标记 `SUCCESS`。
- `SETTLE_AVAILABLE` 必须幂等，重复执行不得重复加钱。

#### 4.8.4 售后仲裁

```text
全额退款：
1. 短事务创建 ARBITRATION_REFUND 任务
2. 资金退款
3. 资产作废
4. 商家退款统计
5. 本地订单 REFUNDED，售后单 RESOLVED

全额放款：
1. 短事务创建 ARBITRATION_RELEASE 任务
2. 复用结算流程
3. 本地订单 SETTLED，售后单 RESOLVED
```

新增订单状态：

| 状态 | 说明 |
| --- | --- |
| `REFUNDING` | 退款执行中 |

仲裁记录在创建任务时先保存 `PENDING`，全部步骤成功后更新 `SUCCESS`。

#### 4.8.5 交付与风控事件

交付本地状态可以先落库，风控事件确认改为异步：

1. 交付本地事务只保存交付记录和订单状态。
2. 事务提交后创建 `RISK_EVENT_CONFIRM` 任务。
3. 风控确认失败只重试，不回滚已完成的交付状态。
4. 超过重试上限转人工，并记录风控事件缺失。

## 5. 对账设计

### 5.1 现有能力

当前已有：

- `TradeReconciliationService`
- `FundService.reconcile()`
- `t_trade_reconciliation_diff`
- `t_fund_reconciliation_diff`
- XXL-Job `tradeFundReconciliationJob`

本批需要扩展，而不是重建。

### 5.2 差异类型

| 差异类型 | 说明 | 处理建议 |
| --- | --- | --- |
| `CALLBACK_WITHOUT_TASK` | 回调通过但无编排任务 | 自动补任务 |
| `TASK_STEP_INTERRUPTED` | 步骤长时间停留中间态 | 自动恢复 |
| `ORDER_AMOUNT` | 订单与结算金额不一致 | 人工处理 |
| `SETTLEMENT` | 结算单与资金流水不一致 | 人工处理 |
| `ESCROW_AMOUNT` | 平台托管总额不一致 | 告警并定位 |
| `ASSET_ORDER_MISMATCH` | 订单状态与资产占用不一致 | 自动补偿或人工 |
| `FUND_TRANSACTION_MISSING` | 业务状态成功但资金流水缺失 | 自动补执行或人工 |

### 5.3 对账任务

新增或扩展 XXL-Job：

```text
tradeConsistencyReconciliationJob
```

建议频率：

1. 编排任务一致性：每 1 分钟。
2. 订单与资金对账：每 5 分钟。
3. 日终全量对账：每日一次。

对账输出：

1. 差异数量。
2. 差异类型分布。
3. 最老差异账龄。
4. 自动恢复数量。
5. 人工待处理数量。

## 6. 模块改造清单

### 6.1 `mall-order-service`

新增组件：

- `PaymentCallbackVerifier`
- `PaymentCallbackService`
- `TradeOrchestrationService`
- `TradeOrchestrationTaskRepository`
- `TradeOrchestrationStepRepository`
- `TradeOrchestrationConsumer`
- `TradeOrchestrationRecoverJobHandler`
- `TradeConsistencyReconciliationService`

修改点：

- `MockPaymentController` 改用 DTO 和 `@Valid`。
- `TradeOrderService.create()` 拆出编排任务创建。
- `handleCallback()` 只做校验、落库和任务创建。
- `settle()` 拆分为任务创建和任务执行。
- `arbitrate()` 拆分为退款任务和放款任务。
- 风险事件确认改为事务后任务。

### 6.2 `mall-item-service`

- 资产预留、确认、释放、作废接收或生成稳定幂等键。
- 重复调用返回上次结果。
- 预留单状态可查询。

### 6.3 `mall-user-service`

- 资金冻结、结算、退款、待结算转可用全部支持幂等键。
- 幂等冲突时返回原事务结果。
- 对账接口支持按业务单号查询资金事务。

### 6.4 `mall-risk-service`

- 事件确认保持幂等。
- 决策结果可按 `eventNo` 查询。
- 命令结果回传失败时可被重试。

### 6.5 `mall-gateway-service`

- 保持回调公开路径。
- 增加回调专用限流。
- 生产环境增加来源控制配置。
- 透传 traceId。

## 7. 开发任务拆分

| 任务 | 内容 | 预估规模 | 依赖 |
| --- | --- | --- | --- |
| B1-01 | 回调 DTO、HMAC 校验器、配置类 | 中 | 无 |
| B1-02 | 回调表和 nonce 表迁移 | 中 | B1-01 |
| B1-03 | 回调 Controller 改造与错误码 | 中 | B1-01、B1-02 |
| B1-04 | 网关限流与来源控制 | 小 | B1-03 |
| B1-05 | 编排任务和步骤表迁移 | 大 | 无 |
| B1-06 | 编排任务领域模型和仓储 | 大 | B1-05 |
| B1-07 | MQ 消费者与恢复任务 | 大 | B1-06 |
| B1-08 | 支付确认编排 | 大 | B1-07 |
| B1-09 | 结算与仲裁编排 | 大 | B1-07 |
| B1-10 | 资产和资金幂等增强 | 大 | 接口设计 |
| B1-11 | 一致性对账扩展 | 中 | B1-08、B1-09 |
| B1-12 | 安全与一致性测试 | 大 | 全部 |

## 8. 测试设计

### 8.1 支付安全测试

必须覆盖：

1. 正确签名通过。
2. 错误密钥签名拒绝。
3. 篡改金额、订单号、支付单号、result、rawPayload 后签名拒绝。
4. 时间戳超窗拒绝。
5. 相同 nonce 重复提交拒绝。
6. 不同 callbackNo、相同 nonce 拒绝。
7. 并发提交相同 nonce 只有一个成功。
8. 重复成功回调只推进一次支付状态。
9. 支付超时后渠道成功回调不自动关闭差异，必须进入任务或对账。
10. 签名失败有告警埋点。

### 8.2 一致性测试

必须覆盖：

1. 资产预留成功，本地订单创建失败，自动释放。
2. 风控拒绝，资产释放。
3. 资金冻结成功，资产确认失败，任务进入补偿或人工。
4. 资产确认成功，本地订单更新失败，任务可恢复。
5. 结算资金成功，结算单插入失败，重试不重复入账。
6. 仲裁退款部分步骤失败，任务可恢复。
7. MQ 消息重复消费，业务只执行一次。
8. MQ 消息丢失，恢复任务接管。
9. 两台实例同时抢占任务，只有一个执行。
10. 对账任务能发现并记录差异。

### 8.3 性能测试

1. 回调校验 P95 < 20ms，不含下游编排。
2. 单实例回调 500 QPS 校验无错误。
3. 编排任务恢复单批 100 个任务 P95 < 5s。
4. 数据库锁等待无持续增长。

## 9. 灰度与回滚

### 9.1 发布顺序

1. 发布数据库迁移。
2. 发布下游幂等能力。
3. 发布订单服务新校验与编排逻辑，默认关闭编排。
4. 灰度 Mock 支付流量，观察回调校验和任务执行。
5. 打开编排开关。
6. 观察对账差异清零后，删除旧回调兼容逻辑。

### 9.2 开关

```yaml
trade:
  orchestration:
    enabled: ${TRADE_ORCHESTRATION_ENABLED:false}
    recover-batch-size: ${TRADE_ORCHESTRATION_RECOVER_BATCH_SIZE:100}
    lock-seconds: ${TRADE_ORCHESTRATION_LOCK_SECONDS:60}
```

### 9.3 回滚策略

- 回调签名异常导致渠道无法回调时，可临时启用旧密钥并行窗口。
- 编排异常时可关闭新流程，但必须保留任务恢复入口，不能直接删除中间态任务。
- 数据库迁移必须可重复执行，新增字段均有默认值。

## 10. 验收标准

1. 外部无法用公开订单信息构造合法回调签名。
2. 回放请求在同一 nonce、不同 callbackNo 场景下全部被拒绝。
3. 时间窗口配置可动态调整。
4. 支付回调通过后，本地事务内不再包含资金和资产远程调用。
5. 所有跨资金和跨资产操作都有任务、步骤、幂等键和补偿定义。
6. 模拟 MQ 丢消息、服务重启、步骤失败后，任务可自动恢复或转人工。
7. 多实例并发测试通过。
8. 对账能发现回调、任务、订单、资产、资金之间的主要不一致。
9. P0 安全与一致性测试全部通过。
10. 生产配置中不出现明文回调密钥。
