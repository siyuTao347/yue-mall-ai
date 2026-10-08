# 第三批开发设计：运营闭环

| 属性 | 内容 |
| --- | --- |
| 版本 | v1.0 |
| 日期 | 2026-09-30 |
| 状态 | 待评审 |
| 对应批次 | 第三批：运营闭环 |
| 涉及模块 | `mall-risk-service`、`mall-order-service`、`mall-user-service`、`mall-item-service`、`mall-gateway-service`、`mall-frontend` |
| 前置文档 | `stage1_stage2_optimization_review.md`、`stage1_stage2_batch1_security_consistency_dev_design.md`、`stage1_stage2_batch2_query_performance_dev_design.md` |

本文是开发设计文档，只描述目标方案、页面结构、接口契约、数据结构、任务拆分和验收标准，不直接修改业务代码。

## 1. 背景与目标

阶段二已经具备风控事件、决策、案件、处理记录和异步命令链路，后端也有案件管理接口，但运营能力仍停留在接口层：

1. 前端交易工作台没有风控案件入口，运营无法完成案件认领、处置、关闭和重开。
2. 案件证据分散在风控事件、决策、案件备注和命令结果中，详情页缺少结构化展示。
3. 案件状态、命令状态、风险等级、场景和处理命令缺少统一中文字典。
4. 资金相关处置缺少明确的二次确认和风险提示。
5. 前端缺少加载、错误、空态、重试、取消和并发操作控制。
6. 命令发送失败、执行失败和死信状态缺少统一处理入口。

本批目标：

- 管理员可以在前端完成风控案件从查询、认领、查看证据、处置、追踪命令到关闭或重开的闭环。
- 案件详情能清晰展示决策、命中规则、事件上下文、身份关系、命令结果和操作历史。
- 高风险和资金相关操作必须输入处理原因，并经过二次确认。
- 页面所有异步状态可见，失败可重试，页面切换和组件卸载时取消无意义请求。
- 同一案件的关键操作具备状态前置条件校验和并发保护，避免重复处理。
- 命令失败和死信案件有明显入口，可重发或转入人工处理。

非目标：

- 不在本批重写风控规则引擎和指标计算，相关性能优化由第二批承接。
- 不引入新的重型工作流平台，继续使用现有案件表、RocketMQ 和 XXL-Job。
- 不把风控工作台扩展为通用工单系统，优先满足虚拟资产交易的风控运营场景。

## 2. 角色与使用场景

| 角色 | 权限 | 主要诉求 |
| --- | --- | --- |
| 风控专员 | `ADMIN`，可按团队限制数据范围 | 快速领取案件，查看证据，执行低风险处置 |
| 风控主管 | `ADMIN`，可执行资金影响处置 | 复核高风险案件，确认冻结、限制、延迟结算等命令 |
| 系统管理员 | `ADMIN`，可访问配置和死信 | 排查命令失败，必要时重发或重开案件 |

典型流程：

1. 专员打开工作台，默认查看 `OPEN` 案件，按风险等级、场景、创建时间筛选。
2. 认领案件后进入详情，查看决策理由、命中规则、事件上下文和身份关系。
3. 选择处理命令，填写原因；资金相关命令弹出确认框，确认影响范围。
4. 提交后页面展示 `PENDING_SEND` / `SENT`，并按固定间隔刷新命令状态。
5. 命令成功后案件可关闭；命令失败或死信时展示失败原因，并提供重发或重开入口。

## 3. 总体架构

```text
mall-frontend
    |
    | /api/admin/risk/**
    v
Spring Cloud Gateway
    |
    | ADMIN 鉴权 + admin-risk 限流 + traceparent 透传
    v
mall-risk-service
    |
    +--> RiskCaseQueryService       案件列表、详情、字典、证据聚合
    +--> RiskCaseOperationService   认领、处置、关闭、重开、重发
    +--> RiskCommandService         命令状态确认与投递
    +--> RiskEvidenceAssembler      事件、决策、备注、身份图谱组装
    |
    +--> RocketMQ risk-command-topic
            |
            +--> mall-order-service / mall-item-service / mall-user-service
                    |
                    +--> confirmCommand(result)
```

设计原则：

1. **读写分离**：列表和详情聚合只做查询，不修改案件状态，避免查询接口隐含写操作。
2. **操作显式化**：每个操作有独立请求 DTO、状态前置条件和审计记录。
3. **证据不可猜测**：前端只根据后端返回的证据结构渲染，不自行拼接敏感字段。
4. **失败是常态**：命令发送、执行、轮询和网络请求都有明确的失败展示与处理动作。
5. **并发安全**：关键操作携带当前状态，服务端用条件更新保证只有一个操作生效。

## 4. 页面信息架构

### 4.1 工作台入口

在 `TradeWorkbench` 的管理员可见 Tab 中新增：

```text
{ key: 'risk', label: '风控案件', adminOnly: true }
```

页面建议拆分为独立组件，避免继续膨胀 `TradeWorkbench.jsx`：

```text
mall-frontend/src/components/risk/
    RiskCaseWorkbench.jsx      页面容器和状态编排
    RiskCaseFilters.jsx        筛选和分页
    RiskCaseList.jsx           案件列表
    RiskCaseDetail.jsx         详情面板
    RiskEvidencePanel.jsx      证据展示
    RiskCommandTimeline.jsx    命令和处理时间线
    RiskOperationDialog.jsx    处置和确认弹窗
    RiskStatusTag.jsx          状态标签
    RiskDictionary.jsx         字典转换工具
    RiskCaseWorkbench.css      样式
```

### 4.2 列表布局

列表页采用“筛选区 + 案件表格 + 详情侧栏”的布局：

```text
+--------------------------------------------------------------------------+
| 状态 | 风险等级 | 场景 | 命令状态 | 时间范围 | 搜索 | 重置 | 刷新       |
+--------------------------------------------------------------------------+
| 案件号 | 风险 | 场景 | 业务对象 | 状态 | 命令 | 处理人 | 更新时间 | 操作 |
+--------------------------------------------------------------------------+
|                          分页器 / 加载态 / 空态                          |
+--------------------------------------------------------------------------+
```

列表字段：

| 字段 | 展示 | 说明 |
| --- | --- | --- |
| `caseNo` | 可复制文本 | 点击后加载详情 |
| `riskLevel` | 彩色标签 | 低、中、高、严重 |
| `scene` | 中文场景 | 订单、支付、交付等 |
| `bizNo` / `subjectId` | 业务对象摘要 | 支持复制，不做自动跳转 |
| `status` | 状态标签 | 待处理、处理中、已处理、已关闭 |
| `commandStatus` | 命令标签 | 未发送、待发送、已发送、成功、失败、死信 |
| `assignedTo` | 处理人 | 未认领显示“未认领” |
| `updatedTime` | 本地时间 | 精确到秒 |
| 操作 | 按状态显示 | 认领、详情、处置、重发、关闭、重开 |

交互要求：

1. 默认查询 `status=OPEN`，`page=1`，`pageSize=20`，按 `updatedTime desc, id desc` 排序。
2. 筛选条件保留在页面状态中，翻页不清空筛选。
3. 查询发起时展示行级骨架屏，不使用整页空白。
4. 列表为空时区分“无数据”和“查询失败”。
5. 移动端优先保证字段和操作可横向滚动，不隐藏关键状态。

### 4.3 详情布局

详情侧栏分为五个区域：

| 区域 | 内容 |
| --- | --- |
| 案件概要 | 案件号、状态、风险等级、评分、场景、业务号、创建和更新时间 |
| 处理信息 | 处理人、处理命令、处理原因、处理时间 |
| 风控证据 | 决策动作、决策理由、命中规则、事件上下文、身份关系 |
| 命令状态 | 当前命令、投递状态、失败原因、重试次数、最近更新时间 |
| 操作历史 | 认领、处置、命令结果、关闭、重开等记录 |

详情页不直接展示原始 JSON。对开发者排查需要的字段，可放在“原始证据”折叠面板中，默认关闭，并做脱敏：

- IP、设备只展示哈希。
- 手机号、身份证、卡密、支付密钥一律不返回。
- 长文本截断，展开后仍限制最大长度。

### 4.4 状态字典

后端提供字典接口，前端不要硬编码分散文案。前端可保留兜底文案，避免接口失败时页面不可用。

| 类型 | 代码 | 中文 |
| --- | --- | --- |
| 案件状态 | `OPEN` | 待处理 |
| 案件状态 | `PROCESSING` | 处理中 |
| 案件状态 | `RESOLVED` | 已处理 |
| 案件状态 | `CLOSED` | 已关闭 |
| 命令状态 | `NONE` | 无命令 |
| 命令状态 | `PENDING_SEND` | 待发送 |
| 命令状态 | `SENT` | 已发送 |
| 命令状态 | `SUCCESS` | 执行成功 |
| 命令状态 | `FAILED` | 发送失败 |
| 命令状态 | `COMMAND_FAILED` | 执行失败 |
| 命令状态 | `DEAD_LETTER` | 死信待人工处理 |
| 风险等级 | `LOW` / `MEDIUM` / `HIGH` / `CRITICAL` | 低 / 中 / 高 / 严重 |

说明：

1. 当前表结构中的死信状态落为 `COMMAND_FAILED`。为避免大规模迁移，接口层可以将其映射为 `DEAD_LETTER` 展示，并保留原代码字段用于审计。
2. `FAILED` 表示生产者投递失败，`COMMAND_FAILED` 表示消费者执行失败或进入死信。
3. 字典接口返回每个状态的颜色语义和排序值，前端统一渲染。

## 5. 后端接口设计

### 5.1 案件列表

```http
GET /api/admin/risk/cases
```

请求参数：

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `page` | 否 | 默认 1 |
| `pageSize` | 否 | 默认 20，最大 100 |
| `status` | 否 | `OPEN`、`PROCESSING`、`RESOLVED`、`CLOSED` |
| `scene` | 否 | 风控场景 |
| `riskLevel` | 否 | 风险等级 |
| `commandStatus` | 否 | 命令状态 |
| `subjectType` | 否 | `USER`、`MERCHANT`、`ITEM`、`ORDER` |
| `subjectId` | 否 | 主体 ID |
| `bizNo` | 否 | 精确查询 |
| `assignedTo` | 否 | 处理人 ID |
| `fromTime` / `toTime` | 否 | 创建时间范围 |
| `keyword` | 否 | 有限字段前缀查询，最长 64 |

响应：

```json
{
  "code": 200,
  "msg": "success",
  "data": {
    "records": [
      {
        "caseNo": "RC202609300001",
        "scene": "ORDER",
        "sceneText": "订单",
        "bizType": "ORDER",
        "bizNo": "TR123",
        "subjectType": "USER",
        "subjectId": 1001,
        "riskLevel": "HIGH",
        "riskLevelText": "高",
        "riskScore": 86,
        "status": "OPEN",
        "statusText": "待处理",
        "assignedTo": null,
        "commandStatus": "NONE",
        "commandStatusText": "无命令",
        "commandRetryCount": 0,
        "reopenCount": 0,
        "createdTime": "2026-09-30T10:00:00",
        "updatedTime": "2026-09-30T10:00:00"
      }
    ],
    "page": 1,
    "pageSize": 20,
    "total": 1,
    "hasMore": false
  }
}
```

实现要求：

1. 使用第二批的 `PageQuery` / `PageResult` 模型。
2. 查询条件走白名单枚举校验，非法值返回 400。
3. 列表查询不返回 `lastCommandJson`、原始 payload 和证据 JSON。
4. 排序固定 `updated_time DESC, id DESC`。

### 5.2 案件详情

```http
GET /api/admin/risk/cases/{caseNo}
```

响应 DTO 结构：

```java
public record RiskCaseDetailDTO(
        RiskCaseSummaryDTO riskCase,
        RiskDecisionEvidenceDTO decision,
        RiskEventEvidenceDTO event,
        List<RiskCaseNoteDTO> notes,
        RiskCommandSnapshotDTO command,
        List<RiskRelationNodeDTO> relations
) {
}
```

各 DTO 的字段要求：

| DTO | 主要字段 |
| --- | --- |
| `RiskDecisionEvidenceDTO` | `decisionNo`、`action`、`actionText`、`riskLevel`、`riskScore`、`reason`、`hitRules` |
| `RiskEventEvidenceDTO` | `eventNo`、`scene`、`eventType`、`bizNo`、`subject`、`payload`、脱敏后的上下文 |
| `RiskCaseNoteDTO` | `noteType`、`operatorId`、`operatorName`、`content`、`beforeStatus`、`afterStatus`、`createdTime`、结构化证据 |
| `RiskCommandSnapshotDTO` | `commandNo`、`command`、`commandText`、`status`、`retryCount`、`lastError`、`sentTime`、`finishedTime` |
| `RiskRelationNodeDTO` | `nodeType`、`nodeId`、`nodeHash`、`relationType`、`weight`、`degree` |

证据聚合规则：

1. 以 `t_risk_case` 为主数据，通过 `decisionNo` 查询决策，通过事件号或业务号查询事件。
2. `hitRulesJson` 反序列化为强类型 DTO，不把 JSON 字符串直接返回给前端。
3. 身份关系默认只查 `depth=1`，展示最多 20 个节点，防止详情页放大查询。
4. 命令快照来自案件字段和最近命令备注，敏感字段必须剔除。
5. 如果某个证据来源缺失，返回空对象并在 `evidenceCompleteness` 中标记，不让整个详情失败。

### 5.3 状态字典

```http
GET /api/admin/risk/dictionaries
```

响应：

```json
{
  "caseStatus": [
    { "code": "OPEN", "text": "待处理", "tone": "warning", "sort": 1 }
  ],
  "commandStatus": [
    { "code": "PENDING_SEND", "text": "待发送", "tone": "info", "sort": 2 }
  ],
  "riskLevel": [
    { "code": "HIGH", "text": "高", "tone": "danger", "sort": 3 }
  ],
  "scene": [
    { "code": "ORDER", "text": "订单", "commands": ["APPROVE", "FREEZE", "LIMIT", "DELAY_SETTLE"] }
  ],
  "noteType": [
    { "code": "COMMAND_FAILED", "text": "命令执行失败", "tone": "danger" }
  ]
}
```

字典可以按 5 分钟本地缓存；接口失败时前端使用兜底字典。

### 5.4 案件操作接口

#### 认领案件

```http
POST /api/admin/risk/cases/{caseNo}/assign
```

请求：

```json
{
  "expectedStatus": "OPEN"
}
```

规则：仅 `OPEN` 可认领；`expectedStatus` 不匹配时返回 409。

#### 处置案件

```http
POST /api/admin/risk/cases/{caseNo}/resolve
```

请求 DTO：

```java
public record RiskCaseResolveRequest(
        String expectedStatus,
        @NotBlank @Size(min = 10, max = 255) String command,
        @NotBlank @Size(min = 10, max = 255) String reason,
        Map<String, Object> actionParams
) {
}
```

规则：

1. 仅 `PROCESSING` 可处置。
2. `command` 必须在字典中该场景允许的命令列表内。
3. `actionParams` 只允许命令需要的字段，拒绝透传任意 JSON。
4. 资金相关命令必须满足额外参数约束。
5. 提交成功后案件变为 `RESOLVED`，命令状态变为 `PENDING_SEND`。

#### 关闭案件

```http
POST /api/admin/risk/cases/{caseNo}/close
```

请求：`expectedStatus`、`reason`。仅 `RESOLVED` 且 `commandStatus=SUCCESS` 可关闭。

#### 重开案件

```http
POST /api/admin/risk/cases/{caseNo}/reopen
```

请求：`expectedStatus`、`expectedCommandStatus`、`reason`。仅 `CLOSED` 可重开。

#### 重发命令

```http
POST /api/admin/risk/cases/{caseNo}/commands/{commandNo}/resend
```

请求：

```json
{
  "expectedStatus": "RESOLVED",
  "expectedCommandStatus": "FAILED",
  "reason": "网络闪断后人工确认重发"
}
```

允许状态：

| 当前命令状态 | 是否允许 | 说明 |
| --- | --- | --- |
| `PENDING_SEND` | 否 | 等待投递结果 |
| `SENT` | 否 | 等待消费结果 |
| `SUCCESS` | 否 | 已成功，无需重发 |
| `FAILED` | 是 | 投递失败，可重发 |
| `COMMAND_FAILED` | 是 | 死信或执行失败，需确认原因后重发 |

#### 人工处理完成

新增命令死信后的兜底入口：

```http
POST /api/admin/risk/cases/{caseNo}/commands/{commandNo}/manual-complete
```

请求：

```json
{
  "expectedCommandStatus": "COMMAND_FAILED",
  "result": "SUCCESS",
  "reason": "已在业务系统人工完成同等处理",
  "evidence": "处理工单或操作记录说明"
}
```

语义：

1. 只登记人工处理结果，不直接修改订单、资金或资产状态。
2. 若业务系统未实际处理，应使用重发或重开案件。
3. 操作会写入 `COMMAND_MANUAL_COMPLETE` 备注，便于审计。

## 6. 数据设计

### 6.1 现有表扩展

为支撑失败原因和轮询，建议对 `t_risk_case` 做小规模扩展：

```sql
ALTER TABLE `t_risk_case`
    ADD COLUMN `command_last_error` varchar(512) DEFAULT NULL COMMENT '命令最近失败原因',
    ADD COLUMN `command_sent_time` datetime(3) DEFAULT NULL COMMENT '命令最近发送时间',
    ADD COLUMN `command_finished_time` datetime(3) DEFAULT NULL COMMENT '命令最近完成时间',
    ADD COLUMN `operation_version` bigint unsigned NOT NULL DEFAULT 0 COMMENT '操作版本号',
    ADD KEY `idx_command_status_time` (`command_status`, `updated_time`);
```

字段规则：

1. `command_last_error` 只保存脱敏后的错误摘要，不保存完整异常堆栈。
2. `command_sent_time` 在生产者投递成功后更新。
3. `command_finished_time` 在收到成功、执行失败或死信结果后更新。
4. `operation_version` 在认领、处置、关闭、重开、重发、人工完成时递增。

### 6.2 并发控制

所有状态变更 SQL 使用条件更新：

```sql
UPDATE t_risk_case
SET status = 'PROCESSING',
    assigned_to = #{operatorId},
    operation_version = operation_version + 1,
    updated_time = #{now}
WHERE case_no = #{caseNo}
  AND status = #{expectedStatus}
  AND operation_version = #{expectedVersion};
```

要求：

1. 前端详情和列表操作必须携带 `operationVersion`。
2. 更新行数为 0 时返回 `RISK_CASE_STATE_CHANGED`，提示用户刷新。
3. 处置和重发还需校验 `expectedCommandStatus`。
4. 生产者投递、消费者回执、人工操作都要走条件更新，不能先查再无条件 `updateById`。
5. `operationVersion` 不用于分页乐观刷新，只用于关键操作并发保护。

### 6.3 证据与审计

继续使用 `t_risk_case_note` 作为操作审计来源，新增推荐 `noteType`：

| `noteType` | 说明 |
| --- | --- |
| `COMMAND_RESEND` | 人工重发命令 |
| `COMMAND_MANUAL_COMPLETE` | 人工确认处理结果 |
| `COMMAND_LAST_ERROR` | 命令失败摘要 |
| `CASE_REFRESH` | 人工强制刷新最新决策，如后续需要 |

`evidenceJson` 统一结构：

```json
{
  "commandNo": "CMD123",
  "command": "FREEZE",
  "expectedStatus": "RESOLVED",
  "operationVersion": 3,
  "result": "FAILED",
  "message": "下游服务暂时不可用"
}
```

禁止写入：

- 密钥、签名、完整支付报文。
- 明文卡密、手机号、身份证。
- 完整异常堆栈。
- 内部数据库连接信息。

## 7. 前端交互设计

### 7.1 请求层改造

`mall-frontend/src/api/client.js` 增强为：

```js
export async function request(url, options = {}) {
  const { timeout = 10000, signal, ...fetchOptions } = options;
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(new DOMException('请求超时', 'TimeoutError')), timeout);

  if (signal) {
    signal.addEventListener('abort', () => controller.abort(signal.reason), { once: true });
  }

  try {
    const res = await fetch(url, { ...fetchOptions, signal: controller.signal });
    const data = await parseResponse(res);
    assertBusinessCode(data);
    return data;
  } catch (error) {
    throw normalizeRequestError(error, url);
  } finally {
    clearTimeout(timer);
  }
}
```

要求：

1. 默认超时 10 秒；命令提交可使用 15 秒。
2. 支持外部 `AbortSignal`，页面切换和筛选变更时取消旧请求。
3. 统一错误对象包含 `code`、`message`、`traceId`、`retryable`。
4. 请求头增加 `X-Request-Id`，便于和网关日志关联。
5. 不在 `client.js` 中输出业务数据日志，只输出错误摘要。

### 7.2 风控 API 封装

在 `tradeApi.js` 中新增 `risk` 命名空间，或拆分为 `riskApi.js`：

```js
export const riskApi = {
  dictionaries: ({ signal } = {}) => get('/api/admin/risk/dictionaries', { signal }),
  cases: (query, { signal } = {}) => get(buildUrl('/api/admin/risk/cases', query), { signal }),
  detail: (caseNo, { signal } = {}) => get(`/api/admin/risk/cases/${caseNo}`, { signal }),
  assign: (caseNo, payload) => post(`/api/admin/risk/cases/${caseNo}/assign`, payload),
  resolve: (caseNo, payload) => post(`/api/admin/risk/cases/${caseNo}/resolve`, payload),
  close: (caseNo, payload) => post(`/api/admin/risk/cases/${caseNo}/close`, payload),
  reopen: (caseNo, payload) => post(`/api/admin/risk/cases/${caseNo}/reopen`, payload),
  resendCommand: (caseNo, commandNo, payload) =>
    post(`/api/admin/risk/cases/${caseNo}/commands/${commandNo}/resend`, payload),
  manualComplete: (caseNo, commandNo, payload) =>
    post(`/api/admin/risk/cases/${caseNo}/commands/${commandNo}/manual-complete`, payload)
};
```

### 7.3 页面状态模型

```js
const initialPageState = {
  phase: 'IDLE', // IDLE | LOADING | REFRESHING | SUCCESS | EMPTY | ERROR
  error: null,
  records: [],
  page: 1,
  pageSize: 20,
  total: 0
};
```

状态处理：

| 状态 | UI |
| --- | --- |
| `LOADING` | 首次加载骨架屏，按钮禁用 |
| `REFRESHING` | 保留旧数据，按钮显示加载图标 |
| `SUCCESS` | 渲染列表 |
| `EMPTY` | 显示筛选条件下无案件，并提供“重置筛选” |
| `ERROR` | 显示错误摘要、traceId、重试按钮 |

要求：

1. 筛选变化先取消上一个请求，再发起新请求。
2. 页码变化使用 `REFRESHING`，不清空旧数据。
3. 请求响应晚于最新请求时丢弃。
4. 错误信息区分网络失败、超时、权限不足、状态冲突和系统错误。
5. 重试最多自动一次，之后只能由用户手动触发。

### 7.4 命令状态轮询

详情页仅在以下状态轮询：

```text
PENDING_SEND / SENT
```

规则：

| 配置 | 建议值 |
| --- | --- |
| 轮询间隔 | 5 秒 |
| 最大轮询时长 | 5 分钟 |
| 页面隐藏 | 暂停 |
| 组件卸载 | 取消请求并停止定时器 |
| 终态 | 停止轮询并刷新详情 |

轮询请求只调用详情接口，不重复执行处置操作。轮询失败两次后停止，并提示手动刷新。

### 7.5 操作并发控制

前端为每个操作维护独立 key：

```text
assign-{caseNo}
resolve-{caseNo}
close-{caseNo}
reopen-{caseNo}
resend-{caseNo}-{commandNo}
manual-complete-{caseNo}-{commandNo}
```

要求：

1. 操作中禁用同 key 按钮和表单提交。
2. 同一案件的基础状态操作互斥，避免同时提交认领和处置。
3. 提交成功后刷新详情和当前页列表。
4. 返回 `RISK_CASE_STATE_CHANGED` 时刷新详情，不自动重复提交。
5. 提交前保留用户填写内容，失败不清空表单。

### 7.6 处置确认与资金操作

所有处置命令必须展示：

1. 案件号和风险等级。
2. 目标对象和业务单号。
3. 处理命令中文含义。
4. 处理后果。
5. 是否影响资金、交易推进或资产可用性。
6. 必填处理原因。

资金相关命令必须二次确认：

| 命令 | 确认要求 |
| --- | --- |
| `DELAY_SETTLE` | 输入订单号或业务号确认，说明结算延迟影响 |
| `FREEZE` | 明确冻结对象和恢复条件 |
| `LIMIT` | 明确限制范围 |
| `REJECT` | 明确驳回后的不可逆影响 |
| `APPROVE` | 高风险案件需输入 `caseNo` 后确认 |

确认框规则：

1. 危险按钮在 1 秒内不可点击，避免误操作。
2. 高风险命令需要勾选“我已确认影响范围”。
3. 严重风险需要输入案件号确认。
4. 提交后立即禁用按钮，直到收到明确成功或失败结果。
5. 二次确认不替代服务端校验，只是用户体验层防护。

### 7.7 证据展示

证据面板分为四组：

1. **命中规则**：表格展示规则编码、规则名、命中值、阈值、风险分。
2. **事件上下文**：展示事件号、场景、事件类型、业务号、金额、时间，payload 中的字段按白名单渲染。
3. **身份关系**：列表展示关联类型、节点哈希、最近出现时间、关联强度；关系超过 20 条时提示“仅展示主要关联”。
4. **命令与操作历史**：时间线展示操作人、操作类型、状态变化、结果和脱敏证据。

展示要求：

- 金额统一两位小数并带货币符号。
- 时间统一本地时间格式。
- 状态标签颜色由字典 `tone` 决定。
- 复制按钮只复制单号，不自动跳转内部系统。
- 证据缺失显示“暂无该项证据”，不用空白区域。

## 8. 网关与安全

现有网关已将 `/api/admin/risk/**` 纳入 ADMIN 路径和限流规则，本批继续复用：

```yaml
mall:
  gateway:
    security:
      admin-paths:
        - "ANY:/api/admin/risk/**"
    rate-limit:
      rules:
        - id: admin-risk
          path-pattern: /api/admin/risk/**
          key-type: USER_OR_IP
          replenish-rate: 2
          burst-capacity: 120
```

补充要求：

1. 服务端 Controller 不信任前端角色，仍从网关透传的认证上下文读取用户。
2. 查询接口对 `subjectId`、`assignedTo` 做数字范围校验。
3. 操作接口记录操作人 ID 和姓名，不允许前端传入操作人。
4. 详情接口只返回管理员可见的脱敏证据。
5. 对重发和人工完成操作增加独立日志与告警。

## 9. 配置设计

配置参考现有 Nacos 风格，使用环境变量占位符：

```yaml
risk:
  workbench:
    command-poll-interval-seconds: ${RISK_COMMAND_POLL_INTERVAL_SECONDS:5}
    command-poll-max-duration-seconds: ${RISK_COMMAND_POLL_MAX_DURATION_SECONDS:300}
    relation-max-depth: ${RISK_CASE_RELATION_MAX_DEPTH:1}
    relation-max-nodes: ${RISK_CASE_RELATION_MAX_NODES:20}
    manual-complete-enabled: ${RISK_COMMAND_MANUAL_COMPLETE_ENABLED:true}
```

配置要求：

1. 生产值保存在 Nacos，代码中只保留安全的本地默认值。
2. `manual-complete-enabled` 可按环境关闭，防止权限体系不完善时被滥用。
3. 轮询配置同时通过字典接口返回给前端，避免前端写死。
4. 配置调整不需要重启前端，详情页刷新后生效。

## 10. 开发任务拆分

| 序号 | 任务 | 主要内容 | 依赖 |
| --- | --- | --- | --- |
| R-01 | 后端 DTO 与字典 | 定义案件、详情、证据、命令 DTO 和状态字典 | 第二批分页模型 |
| R-02 | 查询服务 | 实现分页筛选、总数、详情聚合和证据脱敏 | R-01 |
| R-03 | 数据结构调整 | 增加命令失败信息、完成时间和操作版本 | 无 |
| R-04 | 操作服务 | 改造认领、处置、关闭、重开、重发和人工完成 | R-03 |
| R-05 | Controller 改造 | 请求 DTO、Bean Validation、统一响应和状态码 | R-01、R-04 |
| R-06 | 前端请求层 | 超时、取消、统一错误、traceId | 无 |
| R-07 | 风控 API | 新增 risk API 封装 | R-06 |
| R-08 | 列表页 | 筛选、分页、状态、骨架屏、空态和错误态 | R-05、R-07 |
| R-09 | 详情页 | 概要、证据、命令状态、操作历史 | R-05、R-07 |
| R-10 | 处置闭环 | 确认框、并发控制、命令轮询、失败处理 | R-08、R-09 |
| R-11 | 测试 | 单测、接口测试、并发测试、前端 E2E | R-01 到 R-10 |

建议实施顺序：

1. R-03、R-01、R-02：先把数据契约和查询能力打牢。
2. R-04、R-05：保证操作闭环和并发安全。
3. R-06 到 R-09：页面可用后再接操作。
4. R-10、R-11：最后打磨体验和测试。

## 11. 测试设计

### 11.1 后端测试

| 测试 | 重点 |
| --- | --- |
| `RiskCaseQueryServiceTest` | 分页、筛选、总数、排序、非法参数、敏感字段脱敏 |
| `RiskCaseOperationServiceTest` | 状态前置条件、乐观并发、操作版本递增、审计备注 |
| `RiskCommandServiceTest` | 命令成功、投递失败、执行失败、死信、重发幂等 |
| `RiskCaseAdminControllerTest` | ADMIN 权限、DTO 校验、错误响应、操作人来源 |
| 并发测试 | 两个线程同时认领或处置，只有一个成功 |
| 证据聚合测试 | 决策、事件、备注缺失时详情仍可返回 |

### 11.2 前端测试

| 测试 | 重点 |
| --- | --- |
| 组件测试 | 字典渲染、状态标签、证据面板、确认框 |
| Hook 测试 | 请求取消、轮询停止、错误分类、并发 key |
| E2E 冒烟 | 查询、认领、处置、轮询、关闭 |
| 失败路径 | 接口 500、401、409、超时、Abort |
| 可用性测试 | 键盘操作、焦点管理、移动端布局 |

### 11.3 关键验收用例

1. 管理员打开工作台，默认加载待处理案件，能看到总数并翻页。
2. 认领案件后列表状态变为处理中，详情展示处理人。
3. 两个管理员同时认领同一案件，只成功一次，失败方提示刷新。
4. 处置高风险案件时，必须填写原因并经过二次确认。
5. 提交命令后页面展示待发送或已发送，并在收到结果后自动更新。
6. 模拟命令执行失败，详情展示失败原因，重发按钮可用。
7. 模拟死信案件，人工完成入口只在配置开启时展示。
8. 切换 Tab 或卸载组件后，轮询和未完成请求被取消。
9. 普通用户访问风控接口被网关拒绝。
10. 详情不返回明文敏感信息。

## 12. 发布与回滚

发布步骤：

1. 执行数据库扩展脚本，新增字段和索引。
2. 发布 `mall-risk-service`，兼容旧字段和新接口。
3. 发布前端，新增风控工作台入口，默认对管理员开放。
4. 观察 `admin-risk` 限流、接口 P95、错误率和命令状态流转。

回滚方案：

1. 前端可独立回滚，隐藏风控 Tab 即可。
2. 后端保留旧接口返回结构时，可回退服务版本。
3. 新增字段允许为空或有默认值，不做破坏性回滚。
4. 重发和人工完成接口可通过配置关闭。

## 13. 验收标准

1. 风控案件列表支持分页、筛选、总数、排序和刷新。
2. 详情能完整展示案件、决策、命中规则、事件上下文、身份关系、命令结果和操作历史。
3. 认领、处置、关闭、重开、重发、人工完成均有权限、状态和审计控制。
4. 资金相关命令有二次确认，处理原因必填。
5. 页面具备加载、刷新、空态、错误、重试、取消和并发禁用状态。
6. 命令非终态轮询、终态停止、失败可人工处理。
7. 所有新增接口不返回敏感明文，错误响应包含稳定错误码和 traceId。
8. 关键后端用例和前端 E2E 冒烟通过。

