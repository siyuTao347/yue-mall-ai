# 资金异常调查：数据契约与数据库设计

> 本分册定义[主体方案](development_design.md)和[客服对接](customer_agent_integration.md)共同使用的字段、状态和持久化约束。新接口待开发；[数据库脚本](../../sql/agent/moneyerrorAgent/README.md)已生成，未在数据库执行。

## 1. 通用契约规则

1. 所有时间在接口中使用带时区 ISO-8601；数据库新表统一记录 UTC `datetime(3)`，连接设置明确。现有本地时间字段由来源服务按其实际时区转换，不能假定所有旧列都是 UTC。支付业务日保留 `channelBusinessDate` 与 profile 时区。
2. 金额为十进制字符串，服务内部 `BigDecimal`，持久化 `decimal(18,2)`，首版 CNY；不静默转换币种或舍入有冲突的金额。
3. 编号是 opaque reference，最长按接口限制。UI 不能构造编号来证明权限，服务端不能仅靠编号前缀推断关联。
4. JSON 请求使用明确 schemaVersion、字段长度、数组上限、枚举白名单；未知契约主版本拒绝。内部 DTO 与 A2A SDK 类型隔离。
5. 事实、陈述、假设分别存储。不存在/无权限对外统一为不可访问；超时不返回空数组冒充不存在。
6. 所有客户端重试键绑定 `caller + action + requestId + requestHash`。相同 key 不同载荷返回 `IDEMPOTENCY_CONFLICT`，不得覆盖原请求。

requestHash 基于规范化业务载荷与原主体身份计算，排除短期访问令牌、traceId 等可变化的传输字段。授权续签不改变业务重试键；换主体不能借同一 requestId 读取原回执。

## 2. 标识符与权威源

| 字段 | 生成方/格式建议 | 含义 |
| --- | --- | --- |
| caseNo | Money，`MC` + 32 位大写十六进制 | 持续存在的业务问题 |
| taskNo / A2A taskId | Money，`MT` + 32 位大写十六进制 | 本次独立调查工作；可用 taskNo 作为 Money 端 taskId |
| contextId | Money 生成 | A2A 关联上下文；服务端映射到 caseNo，不是授权凭证 |
| dispatchNo | 客服，`MD` + 32 位大写十六进制 | 客服可靠提交记录 |
| requestId / messageId | 原调用方首次生成并持久化 | 首次委托/追加输入的传输去重 ID |
| inputRequestNo | Money，`MI` + 32 位大写十六进制 | 某次有版本的补充信息请求 |
| reportNo / artifactId | Money，`MR` + 32 位大写十六进制 | 不可变报告版本 |
| projectionRevision | Money，按 case + audienceScope 递增 | 当前调用方可见的进度/报告版本 |
| operationId | 受控命令入口生成 | 一次有业务副作用操作的稳定编号 |
| sourceEventId | 来源服务生成 | Inbox 去重，与业务版本分别存储 |

用户会话的 `sessionNo`、客服 `runNo`、`intentInstanceId` 仅作为关联信息。Money 不使用它们直接授权读写业务。

## 3. 应用层接口与 DTO

### 3.1 客服应用接口

新增 `CustomerMoneyApplicationService` 负责用户授权和本地持久化；`MoneyAgentClient` 只处理远程协议。接口示意：

```java
interface MoneyAgentClient {
    MoneyTaskReceipt start(DelegatedRequest<MoneyInvestigationRequest> request);
    MoneyTaskView get(MoneyTaskReference ref, MoneyCallerCredential credential);
    MoneyCaseView getCase(MoneyCaseReference ref, MoneyCallerCredential credential);
    MoneyTaskView provideInput(DelegatedRequest<MoneyAdditionalInput> input);
    MoneyCancelReceipt cancel(DelegatedRequest<MoneyCancelRequest> request);
}
```

HTTP Controller 不直接暴露 `MoneyCallerCredential`，浏览器只携带正常登录凭据。凭据不进入模型可见 DTO。

### 3.2 `MoneyInvestigationRequest v1`

| 字段 | 类型/约束 | 说明 |
| --- | --- | --- |
| schema | 固定字符串 | `mall.money.investigation-request.v1` |
| requestId | string，1–128 | 原调用方稳定去重键 |
| origin | enum | CUSTOMER / FINANCE / DOMAIN_EVENT |
| subject | object | Order 解析后的 scopeKey、orderNo、可选 attemptNo/refundNo/diffNo |
| problemFamily | enum | 主体方案四类问题族 |
| claim | object | 脱敏原始陈述、sourceMessageRef、陈述时间；不是事实 |
| relatedCaseNo | 可空 string | 仅明确续查已有问题时设置，服务端验证范围 |
| delegationRef | string | 关联受验证授权；票据通过非模型上下文传递 |
| callerContext | object | dispatchNo、customerRunNo、intentInstanceId；可缺省，不能包含整个会话 |
| expectedScopeFingerprint | string | 解析时对象范围摘要，不等于永久有效授权 |

示例为**业务载荷**，由 A2A 适配器封装，不是完整协议报文：

```json
{
  "schema": "mall.money.investigation-request.v1",
  "requestId": "REQ-DEMO-001",
  "origin": "CUSTOMER",
  "subject": {"scopeKey": "ORDER:T202610310001", "orderNo": "T202610310001", "attemptNo": "PA_T202610310001_02", "refundNo": null, "diffNo": null},
  "problemFamily": "EXCESS_PAYMENT",
  "claim": {"text": "这笔订单似乎扣了两次，请核实", "sourceMessageRef": "AM-DEMO-001", "reportedAt": "2026-10-31T10:00:00+08:00"},
  "relatedCaseNo": null,
  "delegationRef": "GRANT-DEMO-001",
  "callerContext": {"dispatchNo": "MD00000000000000000000000000000001", "customerRunNo": "RUN-DEMO-001", "intentInstanceId": "RUN-DEMO-001:i1"},
  "expectedScopeFingerprint": "demo-scope-fingerprint"
}
```

Money 不直接相信 origin、problemFamily、scopeKey 或 relatedCaseNo：根据可信调用身份、授权及业务解析结果重新判定。

### 3.3 受理回执与客户投影

`MoneyTaskReceipt`：`schemaVersion/requestId/caseNo/taskNo/contextId/acceptedAt/reused/projectionRevision`。只有这个回执落入客服绑定记录后，客服才能称远端已受理。HTTP 200、模型文字或本地 dispatch 保存成功均不足以替代它。

`MoneyTaskView` 是指定任务及其当次受众投影；终态后保留当次结果。`MoneyCaseView` 是案件当前受众投影，包含 caseNo、currentTaskNo、contextId 和下表字段；taskState/taskVersion 对应 currentTaskNo。两者经不同协议调用取得，不用动态案件状态改写终态 Task/Artifact。

| 字段 | 内容 |
| --- | --- |
| taskState | A2A 任务语义映射后的应用枚举 |
| caseState | 持续问题状态，不能以 taskState 推断已解决 |
| taskVersion / caseVersion | 服务端任务/问题乐观锁版本，供取消等条件操作使用；与投影版本分开 |
| projectionRevision / projectionHash | 版本与去重依据 |
| publicSummary | 由受众策略生成的可读说明 |
| moneyFacts | 仅当前受众获准的四层事实 |
| inputRequest | 当前有效的输入要求或 null |
| allowedActions | 服务端决定的查询/补充/取消等动作 |
| reportRef / reportRevision | 有权限的报告投影引用 |
| updatedAt / coverage / freshness | 更新时间、证据覆盖与时效 |
| resolutionOutcome | UNDETERMINED / VERIFIED_NORMAL / NEEDS_REVIEW / VERIFIED_RESOLVED / INCONCLUSIVE |

`getCase` 映射到 `money.case-status.v1` 技能的 SendMessage/Message 查询，不分配新 task。案件访问先验证 grant 与 scope；涉及新 currentTaskNo 时创建/复用独立 task_access 后返回允许的视图，原始内部 history 始终不外泄。每次 case 查询允许生成新的 messageId；查询失败重试不产生调查副作用。

`completed + NEEDS_REVIEW` 是有效组合，表示调查产物完成、问题还要人工处理。客服显示“调查材料已整理，等待复核”。

### 3.4 补充输入

`MoneyAdditionalInput` 包含 `requestId/caseNo/taskNo/inputRequestNo/expectedInputVersion/answers/sourceMessageRef`。字段只允许输入请求 schema 中定义的类型，例如付款方式、用户观察到的时间、是否两次操作、脱敏报错文字。

答案提交要求：确认当前调用者是该 inputRequest 的目标主体；任务确实处于可接受输入状态；版本一致；字段白名单和内容长度合法；重复请求可重放。过期、已替代、他人输入请求均拒绝。

同一次输入成功后的重试返回原回执，而不是报“任务已结束”诱导用户再次提交。重复键识别与载荷冲突检查应先于状态迁移校验，但必须先鉴权。

### 3.5 范围与资金查询 DTO

`ResolveMoneySubjectResult`：`canonicalOrderNo/buyerRef/sellerRef/allowedView/verifiedAttemptRefs/verifiedRefundRefs/scopeProofRef/scopeFingerprint`；buyer/seller 原始 ID 仅内部服务可见。

`MoneyAccessContext`：`serviceIdentityProof/delegationProof/scopeProofRef/action/requestId/traceId`。DTO 的业务字段由接收方重验，不能复制一个 userId 就获得权限。Order 的 scope proof 绑定查出的真实对象集合、接受方服务、有效期与授权 grant；User 按该证明限制精确事务查询。

`MoneySnapshotQuery`：`scopeKey/subjectRefs/cursor/pageSize/asOfRequestTime`。不是 SQL，也不允许任意 filter 表达式。`asOfRequestTime` 是取证意图，跨服务读并不天然形成同一时刻快照。

`MoneyFundQuery`：`scopeProofRef/orderNo/verifiedAttemptRefs/verifiedRefundRefs/operationRefs`；最多 50 个引用，必须全部在范围证明内。User 服务内部生成允许的精确幂等键，调用方不能直接查询任意 idempotencyKey。

## 4. 事实与报告契约

### 4.1 事实统一包装

```json
{
  "factId": "F1",
  "kind": "CHANNEL_REFUND_OBSERVATION",
  "sourceService": "mall-order-service",
  "sourceRef": "OBS-DEMO-001",
  "sourceVersion": "sha256:demo",
  "observedAt": "2026-10-31T10:01:00+08:00",
  "occurredAt": null,
  "businessDate": "2026-10-31",
  "environment": "MOCK",
  "scopeKey": "ORDER:T202610310001",
  "data": {"refundNo": "RF_T202610310001_A1B2C3D4", "exists": true, "rawStatus": "PROCESSING", "amount": "100.00", "currency": "CNY"},
  "quality": {"identityMatched": true, "coverage": "COMPLETE", "linkQuality": "EXACT", "conflict": false}
}
```

sourceVersion 优先真实 version/序号；某些已有 updateById 未明确递增版本时，计算相关字段的稳定 hash 并保留读取时间，不能假设数据库 version 足够可靠。

事实 ID 在报告输入内唯一，服务端维护 `factId → snapshot/sourceRef` 映射。模型不能自创 factId，也不能自行填 `identityMatched=true`。对外展示的金额、状态和时间由事实投影程序填写。

### 4.2 覆盖与一致性

每次快照包含 `sources[]`，每项为 OK / NOT_FOUND / UNAVAILABLE / FORBIDDEN / TRUNCATED / LEGACY_UNLINKED，另带分页是否完整。仅全部必需源可用且集合完整时，才可以形成“没有额外款项”这种否定结论。

跨 Order/User/渠道读取按各自 observedAt 保留，不宣称原子快照。若读取期间订单/支付关键状态改变，最多重新取一次；仍不稳定时返回 `INCONSISTENT_WINDOW`，暂停建议写操作。业务执行时再次检查当前条件。

### 4.3 内部报告

`MoneyInvestigationReport v1` 字段：

```text
reportNo, reportRevision, caseNo, taskNo, schemaVersion
subject, problemFamily, verifiedFindings[]
facts[] / factRefs[], claims[], hypotheses[]
fundPath[]                    # 每个节点必须引用事实，不是模型凭空绘制
conflicts[], missingEvidence[], coverage, freshness
recommendedActions[]          # 建议，不是授权/执行结果
outcome, stopReason, basedOnFingerprint
ruleVersion, promptVersion, modelVersion, createdAt
```

每个 finding 有 `code/supportingFactIds/contradictingFactIds/certainty(VERIFIED|PARTIAL|UNCONFIRMED)`。不把模型概率作为资金真相。

### 4.4 用户报告投影

`MoneyPublicProjectionBuilder` 根据 audienceScope 生成另一份数据，只允许以下字段：caseNo、orderNo、问题摘要、阶段、用户自己的支付/退款事实、缺失输入、可操作项、更新时间、MOCK 标识。

例如退款状态冲突时，生成“订单记录显示已进入退款处理，但渠道结果仍为处理中，尚无法确认到账”。该文本不由客服再次自由改写为“已退款成功”。内部 financial events、账单原文、平台余额、其他用户资料都不进入投影。

## 5. 状态机：唯一口径

### 5.1 问题单状态

| 状态 | 含义 | 允许迁移 |
| --- | --- | --- |
| OPEN | 已受理，正在调查/等候执行 | NEED_INPUT / NEED_REVIEW / WAITING_BIZ / RESOLVED / CLOSED / CANCELED |
| NEED_INPUT | 需要指定主体提供资料 | OPEN / NEED_REVIEW / CLOSED / CANCELED |
| NEED_REVIEW | 调查产物已有，需财务处理 | OPEN（新任务）/ WAITING_BIZ / RESOLVED / CLOSED |
| WAITING_BIZ | 经授权业务操作已受理，等待结果 | OPEN（重新调查）/ NEED_REVIEW / RESOLVED |
| RESOLVED | 权威事实验证问题已解决 | 终态；新异常建新 episode |
| CLOSED | 人工明确结束，可能未能查明 | 终态，必须有 reason/outcome，不算真实解决 |
| CANCELED | 无未决业务命令时取消调查 | 终态，不能表示退款撤回成功 |

只有 RESOLVED/CLOSED/CANCELED 释放活动去重键；报告完成不释放。WAITING_BIZ 不能被用户取消直接改成 CANCELED，先确认业务命令状态。

### 5.2 调查 Task 状态

内部采用 `SUBMITTED/WORKING/INPUT_REQUIRED/AUTH_REQUIRED/COMPLETED/FAILED/CANCELED/REJECTED`，由 A2A 适配器映射到锁定版协议枚举。Task 终态不恢复；重分析新建 taskNo，继承 caseNo/contextId 与相关报告引用。

任务等待用户时为 INPUT_REQUIRED；缺授权时为 AUTH_REQUIRED。财务报告已产出并排队审核时，可以结束本次 Task、将 case 设 NEED_REVIEW。不能用“等待授权”的状态伪装财务已经批准。

### 5.3 客服本地提交状态

`PENDING → SENDING → ACCEPTED`；暂时失败 `RETRY_WAIT → SENDING`；永久错误 `REJECTED`；始终不知远端受理情况 `UNKNOWN → 相同请求恢复`，不能换 requestId。状态与最后错误、nextAttemptAt 一起持久化。

### 5.4 建议与业务命令

建议：`DRAFT → PENDING_REVIEW → APPROVED/REJECTED/STALE`。

执行：`NOT_SUBMITTED → SUBMITTING → ACCEPTED → RUNNING → SUCCEEDED/FAILED/UNKNOWN`。UNKNOWN 是调查系统对命令结果的认知状态；业务侧通过查询给出权威回执。APPROVED 不直接显示为已执行。

## 6. 数据库总体约定

新库/表的业务编号使用 ASCII 大小写敏感排序，正文 JSON 用 utf8mb4。新表使用 InnoDB；单行 JSON 大小限制在应用层实现，单份快照建议不超过 256 KiB、报告不超过 128 KiB，超出以受控证据引用拆分，不截断关键数据。

以下完整 DDL 展示最关键的并发约束。其他表列规格在后续小节逐字段给出，已据此生成独立 SQL 文件；实施时仍须验证实际数据库结构与代码契约。所有外键跨库均为逻辑引用，不建立跨服务数据库外键；清理由应用按引用关系处理。

### 6.1 `db_money_agent.t_money_case`

```sql
CREATE TABLE t_money_case (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  case_no VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  scope_key VARCHAR(192) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  problem_family VARCHAR(32) NOT NULL,
  target_ref VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  active_dedupe_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  previous_case_no VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NULL,
  order_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  origin VARCHAR(24) NOT NULL,
  state VARCHAR(24) NOT NULL DEFAULT 'OPEN',
  outcome VARCHAR(32) NOT NULL DEFAULT 'UNDETERMINED',
  severity VARCHAR(16) NOT NULL DEFAULT 'NORMAL',
  owner_staff_id BIGINT NULL,
  current_task_no VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NULL,
  latest_report_no VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NULL,
  grant_ref VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  event_seq BIGINT NOT NULL DEFAULT 0,
  version BIGINT NOT NULL DEFAULT 0,
  close_reason VARCHAR(512) NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  closed_at DATETIME(3) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_money_case_no (case_no),
  UNIQUE KEY uk_money_case_active (active_dedupe_key),
  KEY idx_money_case_order (order_no, state, id),
  KEY idx_money_case_worklist (state, severity, updated_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

关闭时在同一事务将 active_dedupe_key 置 NULL，允许历史多行存在。命中活动唯一键后返回已有 case 前仍需核验对象范围与调用者可见性。

### 6.2 `db_money_agent.t_money_task`

```sql
CREATE TABLE t_money_task (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  task_no VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  case_no VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  context_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  execution_grant_ref VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  execution_scope_json JSON NOT NULL,
  execution_scope_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  state VARCHAR(24) NOT NULL DEFAULT 'SUBMITTED',
  wait_reason VARCHAR(32) NULL,
  checkpoint_json JSON NULL,
  input_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  next_run_at DATETIME(3) NULL,
  lease_owner VARCHAR(64) NULL,
  lease_until DATETIME(3) NULL,
  fencing_token BIGINT NOT NULL DEFAULT 0,
  plan_rounds INT NOT NULL DEFAULT 0,
  tool_calls INT NOT NULL DEFAULT 0,
  input_tokens BIGINT NOT NULL DEFAULT 0,
  output_tokens BIGINT NOT NULL DEFAULT 0,
  cost_microunits BIGINT NOT NULL DEFAULT 0,
  version BIGINT NOT NULL DEFAULT 0,
  last_error_code VARCHAR(64) NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  finished_at DATETIME(3) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_money_task_no (task_no),
  KEY idx_money_task_case (case_no, id),
  KEY idx_money_task_claim (state, next_run_at, lease_until, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

cost_microunits 的计价币种由环境固定并写入评测元数据；它用于预算而非平台资金记账。claim 时增长 fencing_token；所有 checkpoint、报告发布和续租必须带 token+version 条件。

### 6.3 `db_money_agent.t_money_request`

```sql
CREATE TABLE t_money_request (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  caller_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  action VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  request_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  payload_json JSON NOT NULL,
  subject_scope_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  grant_ref VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  case_no VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NULL,
  task_no VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NULL,
  state VARCHAR(24) NOT NULL,
  receipt_json JSON NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_money_request (caller_id, action, request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

接受请求、创建/复用 case、创建/复用 task、保存访问绑定和回执同事务。payload_json 仅保存脱敏业务载荷和陈述来源，不保存凭据，应用限制 32 KiB。授权不同的用户即使同属 customer 服务，也不能凭猜到 requestId 重放他人的结果；读取前比对 grant 对应主体、subject_scope_hash 及当前权限。

### 6.4 Money 其他表：完整列规格

公共列约定：每张表都有 `id BIGINT UNSIGNED PK AUTO_INCREMENT`、`created_at DATETIME(3)`；标记“可变”的表另有 `updated_at DATETIME(3)`、`version BIGINT NOT NULL DEFAULT 0`。编号/散列列使用前述 ASCII 规则，未标 NULL 的列均非空。

| 表 | 业务列（类型） | 唯一键与查询索引 |
| --- | --- | --- |
| `t_money_snapshot` 不可变 | snapshot_no varchar(40), task_no varchar(40), step_no int, source_kind varchar(32), fingerprint char(64), coverage_json json, facts_json json, observed_at datetime(3) | UK snapshot_no；UK(task_no,step_no,source_kind)；IDX(task_no,id) |
| `t_money_report` 不可变 | report_no varchar(40), case_no varchar(40), task_no varchar(40), revision bigint, schema_version int, based_on_fingerprint char(64), internal_json json, report_hash char(64), outcome varchar(32), rule_version varchar(32), prompt_version varchar(32), model_version varchar(64) | UK report_no；UK(case_no,revision)；IDX(task_no,id) |
| `t_money_projection` 可变 | case_no varchar(40), audience_scope varchar(128), revision bigint, report_no varchar(40) NULL, public_json json, content_hash char(64), latest_case_event_seq bigint, policy_version varchar(32) | UK(case_no,audience_scope)；IDX(updated_at,id) |
| `t_money_input_request` 可变 | input_no varchar(40), case_no varchar(40), task_no varchar(40), audience_scope varchar(128), input_version int, state varchar(24), schema_json json, answer_json json NULL, answer_source_ref varchar(128) NULL, expires_at datetime(3) | UK input_no；IDX(task_no,state)；IDX(state,expires_at) |
| `t_money_task_input` 不可变 | task_no varchar(40), input_seq bigint, request_row_id bigint, input_kind varchar(32), input_request_no varchar(40) NULL, grant_no varchar(64), payload_hash char(64), content_json json | UK(task_no,input_seq)；UK(task_no,request_row_id)；IDX(task_no,id) |
| `t_money_grant` 可变 | grant_no varchar(64), issuer varchar(64), subject_type varchar(24), subject_id varchar(64), scope_json json, allowed_actions_json json, allowed_receivers_json json, policy_version varchar(32), state varchar(24), expires_at datetime(3), revoked_at datetime(3) NULL | UK grant_no；IDX(state,expires_at) |
| `t_money_task_access` 可变 | task_no varchar(40), grant_no varchar(64), caller_id varchar(64), audience_scope varchar(128), state varchar(24), expires_at datetime(3) | UK(task_no,grant_no,audience_scope)；IDX(grant_no,state)；IDX(task_no,state) |
| `t_money_event` 不可变 | event_no varchar(40), case_no varchar(40), case_seq bigint, task_no varchar(40) NULL, event_type varchar(64), payload_ref_json json, visibility varchar(24) | UK event_no；UK(case_no,case_seq)；IDX(case_no,id) |
| `t_money_tool_call` 不可变 | task_no varchar(40), call_seq int, tool_name varchar(64), target_ref varchar(128), input_hash char(64), fact_ref_json json NULL, outcome varchar(24), error_code varchar(64) NULL, latency_ms int | UK(task_no,call_seq) |
| `t_money_action_proposal` 可变 | proposal_no varchar(40), case_no varchar(40), report_no varchar(40), action_type varchar(64), target_ref varchar(128), params_json json, params_hash char(64), evidence_fingerprint char(64), expected_versions_json json, state varchar(24), proposer_ref varchar(64), reviewer_id bigint NULL, review_reason varchar(512) NULL, reviewed_at datetime(3) NULL, expires_at datetime(3), operation_id varchar(64) NULL | UK proposal_no；UK operation_id（可空）；IDX(case_no,state,id) |

`audience_scope` 示例为 `BUYER:<userId>`、`CUSTOMER_STAFF:<授权范围ID>`、`FINANCE:<岗位范围ID>`。它是服务端计算值，不能由用户任意指定。projection 按授权范围保存或按需生成；GetTask 必须选择调用方视图，不返回 entire internal_json。

case.grant_ref 只记录首次创建的授权来源，不授权所有后续调用。GetTask/Artifact/追加输入都必须校验当前有效的 task_access、grant 和对象范围，不能返回其他调用方原始 Message 历史。task.execution_grant_ref 与 execution_scope_* 固定本次取证权限；新增访问绑定不合并或扩大 Worker 权限。执行授权失效则暂停为 AUTH_REQUIRED；其他受众的查看授权仍独立判断，权限更高的财务入口需显式发起后续任务，不能静默接管原用户授权。

首次陈述、后续补充陈述和输入请求答案都追加到 task_input，input_kind 分别为 INITIAL_CLAIM / ADDITIONAL_CLAIM / REQUESTED_ANSWER。input_seq 在任务锁下递增并进入 checkpoint/input_fingerprint，重启不丢输入。陈述仍不是已核实事实。

GetTask 的终态 Artifact 必须由该 task 的不可变 report 生成受众视图，不从案件当前 projection 取值；受众权限重新校验可以收窄或拒绝返回，但不得替换为下一任务的报告。case-status 才读取当前 projection。

### 6.5 Inbox/Outbox

`t_money_source_inbox`（可变）列：source varchar(64)、event_id varchar(128)、payload_hash char(64)、aggregate_type varchar(32)、aggregate_ref varchar(128)、aggregate_version varchar(64) NULL、schema_version int、payload_json json、state varchar(24)、attempt_count int、next_attempt_at datetime(3)、last_error varchar(64) NULL。UK(source,event_id)，IDX(state,next_attempt_at,id)。

`t_money_outbox`（可变）列：event_no varchar(40)、destination varchar(64)、payload_json json、state varchar(24)、attempt_count int、next_attempt_at datetime(3)、lease_owner varchar(64) NULL、lease_until datetime(3) NULL、fencing_token bigint、last_error varchar(64) NULL。UK(event_no,destination)，IDX(state,next_attempt_at,lease_until,id)。

destination 是配置中的服务别名，禁止数据库载荷中的任意公网 URL。通知对方“有新投影”，不把完整内部报告塞进事件。

## 7. 客服库增量

保留 `db_agent` 为会话权威源。Money 服务不连接它的数据库。

### 7.1 任务绑定 DDL

```sql
CREATE TABLE t_agent_money_binding (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  case_no VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  task_no VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  remote_context_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  order_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  audience_scope VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  origin_session_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  current_session_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  grant_ref VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  case_state VARCHAR(24) NOT NULL,
  task_state VARCHAR(24) NOT NULL,
  subscription_state VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
  projection_revision BIGINT NOT NULL DEFAULT 0,
  projection_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  projection_json JSON NULL,
  next_poll_at DATETIME(3) NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_agent_money_binding (user_id, case_no, audience_scope),
  KEY idx_agent_money_poll (subscription_state, case_state, next_poll_at, id),
  KEY idx_agent_money_order (user_id, order_no, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

同一 case 新 task 以 CAS 更新 task_no，旧 task 关联保留在 dispatch 与历史事件中；过期旧 task 回包不能覆盖新 task 状态。

subscription_state 为 ACTIVE / MUTED / REVOKED。MUTED 表示用户取消主动进展通知，仍可在有效授权下主动查询；REVOKED 表示授权撤回，停止远程读取。只有取消该用户订阅的场景不改变共享 case/task 状态。case 仍活动时不能因 task 已 COMPLETED 而停止恢复扫描；任务查询与案件查询共享 next_poll_at 调度，依据两层状态选择相应调用。

### 7.2 客服其他新表

以下表具有与 6.4 相同的公共列（可变表含 version/updated_at）：

| 表 | 核心列规格 | 约束 |
| --- | --- | --- |
| `t_agent_money_dispatch` 可变 | dispatch_no varchar(40), user_id bigint, session_no varchar(64), run_no varchar(64) NULL, intent_instance_id varchar(96) NULL, action varchar(32), request_id varchar(128), request_hash char(64), payload_json json, grant_ref varchar(64), state varchar(24), remote_task_no varchar(40) NULL, receipt_json json NULL, attempts int, next_attempt_at datetime(3), lease_until datetime(3) NULL, lease_owner varchar(64) NULL, fencing_token bigint, last_error varchar(64) NULL | UK dispatch_no；UK(user_id,action,request_id)；IDX(state,next_attempt_at,lease_until,id) |
| `t_agent_money_delivery` 可变 | delivery_no varchar(40), case_no varchar(40), projection_revision bigint, recipient_user_id bigint, delivery_kind varchar(32), session_no varchar(64) NULL, message_no varchar(64) NULL, expected_control_epoch bigint NULL, state varchar(32), payload_hash char(64), next_attempt_at datetime(3) NULL, last_error varchar(64) NULL | UK delivery_no；UK(case_no,projection_revision,recipient_user_id,delivery_kind)；IDX(state,next_attempt_at,id) |
| `t_agent_money_card` 可变 | card_id varchar(40), session_no varchar(64), user_id bigint, case_no varchar(40), input_no varchar(40) NULL, card_type varchar(32), revision bigint, projection_revision bigint, allowed_actions_json json, public_card_json json, state varchar(24), expires_at datetime(3) NULL | UK card_id；IDX(session_no,case_no,id) |
| `t_agent_money_notice_inbox` 可变 | source varchar(64), event_id varchar(128), payload_hash char(64), payload_json json, state varchar(24), next_attempt_at datetime(3), attempts int, last_error varchar(64) NULL | UK(source,event_id)；IDX(state,next_attempt_at,id) |
| `t_agent_money_staff_note` 不可变 | case_no varchar(40), projection_revision bigint, session_no varchar(64), assigned_staff_id bigint NULL, public_for_staff_json json, based_on_control_epoch bigint | UK(case_no,projection_revision,session_no) |

input 提交同样使用 dispatch 表 `action=PROVIDE_INPUT`，无需另造不兼容的操作去重体系。卡片 revision 来自服务端存储，浏览器传入 revision 只用于比较。

`t_agent_money_staff_note` 保存授权坐席可见的摘要；不把内部财务报告写到现有公开会话事件表。待人工发给用户时走受控发送入口，重新校验投影时效。

## 8. Order/User 增量

### 8.1 Order 只读观察与业务命令

`db_order.t_money_channel_observation`：observation_no varchar(40) UK、scope_key varchar(192)、attempt_no varchar(64)、refund_no varchar(64) NULL、profile_id varchar(64)、profile_version int、request_id varchar(128)、observation_json json、result_hash char(64)、observed_at datetime(3)、created_at datetime(3)。索引(attempt_no,observed_at,id)，(refund_no,observed_at,id)。证据只能由查询适配器产生；是否用于补确认还须检查有效期和来源匹配。

`db_order.t_money_repair_command`（第二阶段）：operation_id varchar(64) UK、proposal_no varchar(40) UK、action_type varchar(64)、scope_key varchar(192)、params_hash char(64)、params_json json、approval_ref varchar(64)、expected_versions_json json、state varchar(24)、business_task_ref varchar(64) NULL、result_json json NULL、attempt_count int、next_run_at datetime(3) NULL、lease/fencing/version/created_at/updated_at。索引(state,next_run_at,id)。

Order 另建/复用合适的领域 Outbox。当前 `t_mock_notification_outbox` 是 Mock 渠道通知，不拿来充当通用 Agent 事件表。新增 `t_money_domain_outbox` 字段沿用 6.5 模式，由 Order 自己管理发布。

### 8.2 User 业务关联

```sql
CREATE TABLE t_fund_business_ref (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  transaction_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  ref_type VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  ref_no VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  link_origin VARCHAR(24) NOT NULL,
  created_at DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_fund_business_ref (transaction_no, ref_type, ref_no),
  KEY idx_fund_business_lookup (ref_type, ref_no, transaction_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

ref_type 限定 ORDER / ATTEMPT / REFUND / OPERATION。新业务原子写入，旧数据回填标 `VERIFIED_BACKFILL`，不得以金额猜测补关联。`FundService.execute` 的幂等重放应校验已有引用与本次业务参数兼容；引用补齐在同事务中执行，不能允许同一交易被绑定给其他订单。

为支持主体设计第 11 节的资金源事件，User 同样新增本库的 `db_user.t_money_domain_outbox`，字段和索引沿用 6.5 的 Outbox 规格。它与 Order 的同名表互相独立，必须由 User 在资金事务/关联变更的同一本地事务内写入，不跨库写 Order Outbox。

### 8.3 现有表的最小索引增量

先核对 `information_schema.statistics` 和 EXPLAIN，再编写幂等迁移；不重复添加已有索引。候选索引：

- `t_payment_attempt(order_no,id)`；已有 payment_no/attempt_no 索引继续使用。
- `t_payment_refund_order(original_attempt_no,status,id)`；order_no 索引按现有迁移核对。
- `t_payment_financial_event(order_no,id)`、`(biz_type,biz_no,id)`。
- `t_recon_diff(order_no,status,id)`；按 diffNo 回溯使用现有唯一键。
- `t_fund_flow(transaction_no,id)`；资金事务精确幂等键使用现有唯一约束。

查询只补必要索引，不让 Agent 运行扫描全部账本的临时 SQL。

## 9. 事务、锁与发布顺序

Money 复合写事务统一按 request（如有）→ grant（按 grantNo 排序）→ case → task → input/access/report/event 的顺序加锁。grant 的注册、续签、撤回与发布并发约定见[独立微服务改造设计第 5.4 节](independent_microservice_refactoring.md#54-grant-注册续签和撤回落点)。Worker 领取租约的短事务可只锁 task，但必须先提交，再进入需要 grant/case 锁的发布事务；不得拿着 task 锁反向等待授权或 case。

### 9.1 创建任务

鉴权与源范围解析（事务外）→ 本地事务插入 request 去重 → 注册/锁定并重验 grant → 插入/复用并锁定 case → 检查 currentTask，复用相容的非终态任务或在无非终态任务时新建 → 写入 task_access → 事件/投影 → 回执落库 → commit → 返回受理。所有任务创建入口必须经过 case 行锁及 currentTask 检查，保证首版每案最多一个非终态任务。范围不相容时按改造方案返回 CASE_TASK_BUSY，不登记虚构排队任务。唯一键冲突只捕获 DuplicateKeyException；数据库不可用不能当重复成功。

相同请求重放原受众回执；不同请求附着于同案时生成各自绑定和受限视图。新陈述需重跑时，将其作为版本化输入保存，并用 CAS 增加任务版本和 fencing token 使旧 Worker 失效；不能覆盖已发布的终态报告。

### 9.2 提交补充信息

鉴权 → request 去重 → 按 grant、case、task、input_request 顺序加锁 → 比较版本/状态/目标受众 → 保存答案并追加 task_input → 标记已提交 → task 更新为 SUBMITTED 并增加版本/fencing → case OPEN → 保存事件 → 回执 → commit。

同一 inputRequest 两个不同输入并发：只有一个在预期版本上成功，另一个返回 `INPUT_VERSION_CONFLICT`；不能后写覆盖前写。

### 9.3 发布报告

事务外生成报告 → 校验事实和受众 → 事务中按 grant、case、task 顺序加锁并验证授权仍有效、租约未过期、fencing token/任务版本/事实指纹 → 分配 reportRevision、caseSeq → 保存不可变 report 和新 projection → 更新任务与 case → 创建 Outbox → commit。

报告完成与 case 已解决分别判定。新事件已经改变事实指纹时，旧报告可以存为过期审计，但不得成为 current projection。

这里验证的是 Money 已观察到的最新版本/事件和输入指纹，不能宣称跨 Order/User/渠道全局原子一致。投影始终包含 asOf/observedAt 与 freshness；尚未送达的源事件可能使其随后过期，任何后续业务写操作必须由权威服务重新校验实时条件。

### 9.4 客服消息原子投递

在 `db_agent` 同事务锁 binding、session、delivery，检查最新 projectionRevision、归属、mode 和 controlEpoch，再写消息、公开事件、Outbox、delivery 回执与卡片。锁顺序统一为 binding → session → delivery/card，避免后台 Worker 与用户动作死锁。

初次入库 dispatch 与最终客服回复可能处于不同事务。即使本轮模型回复失败，dispatch 不能消失；用户重试通过相同来源消息/操作键查到原记录。

## 10. 迁移与兼容

本功能新增的数据库 SQL 文件已放到项目 `doc/sql/agent/moneyerrorAgent`。当前已生成文件，尚未在 MySQL 执行或创建实际数据库表；后续结构增量继续放在该目录。

目录名称不改变数据库归属：调查自有表仍属于 db_money_agent，客服桥接表属于 db_agent，订单与资金关联表分别属于 db_order/db_user。脚本使用 `money_error_` 前缀，实际文件如下；执行顺序、前置结构与验证状态见[SQL 说明](../../sql/agent/moneyerrorAgent/README.md)：

1. [money_error_v1_db_money_agent_core.sql](../../sql/agent/moneyerrorAgent/money_error_v1_db_money_agent_core.sql)：Money 自有表。
2. [money_error_v1_db_agent_money_bridge.sql](../../sql/agent/moneyerrorAgent/money_error_v1_db_agent_money_bridge.sql)：客服绑定、提交、卡片、通知与内部便笺。
3. [money_error_v1_db_order_money_query.sql](../../sql/agent/moneyerrorAgent/money_error_v1_db_order_money_query.sql)：观察记录与订单领域 Outbox。
4. [money_error_v1_db_user_fund_business_ref.sql](../../sql/agent/moneyerrorAgent/money_error_v1_db_user_fund_business_ref.sql)：资金引用与资金源事件 Outbox。
5. [money_error_v1_customer_money_intents.sql](../../sql/agent/moneyerrorAgent/money_error_v1_customer_money_intents.sql)：默认禁用的客服意图元数据和示例；向量由现有 release 构建流程生成。
6. [money_error_v2_money_repair_command.sql](../../sql/agent/moneyerrorAgent/money_error_v2_money_repair_command.sql)：后续修复命令表，相关一致性验收后才启用能力。
7. [money_error_verify.sql](../../sql/agent/moneyerrorAgent/money_error_verify.sql)：只读核对表、字段、唯一键、种子和现有业务索引。

当前脚本只新增所需表及种子，未盲目添加 8.3 的旧表候选索引；这些索引需在实际查询和 EXPLAIN 验证后单独迁移。建表 IF NOT EXISTS 不核验同名表结构，执行前后必须对照脚本验证。

发布采用 expand → 部署兼容代码 → 回填可信引用 → 构建新意图库快照 → 灰度开功能。回滚先关新建/写操作，仍保留查询与未决任务跟踪；不删除历史案件、资金引用或回执表。

若旧版客服无法识别新意图，先撤销/切回旧意图库版本再回滚应用；不能让旧代码读取包含新 route_node 的活动快照。任务和报告保留 schemaVersion，滚动发布必须同时支持当前和上一契约版本。
