# 资金异常调查 Agent（Money Agent）独立微服务实施进度跟踪

> 依据最新独立微服务改造方案：`independent_microservice_refactoring.md`、`microservice_refactoring_tasks.md`、`implementation_review.md`。
> 记录 R（事实源修复）→ S（独立运行时）→ A（A2A协议）→ C（客服闭环）各阶段代码变更、测试断言证据与工程验证。
> 更新日期：2026-10-31

---

## 1. 总体实施状态概览

| 批次 | 交付目标 | 状态 | 验证说明 |
| :--- | :--- | :---: | :--- |
| **R** (修复事实源) | 解决审查 R01—R12：鉴权与票据分离、事实语义严格化、只读边界锁定、历史关联修复、对账与时区标准化、游标分页与指纹 | **已完成** | `mall-api`, `mall-order-service`, `mall-user-service` 修复完毕，相关单元/集成测试 100% 通过（Order 82 tests, User 28 tests, API 16 tests）。 |
| **S** (独立调查服务) | `mall-money-agent-service` 独立 Spring Boot 3.5.6 微服务，单数据源 `db_money_agent`，可执行 fat-jar 打包，15 张核心表实体/Mapper，有界 Graph 调查引擎，报告与受众投影，最小管理控制台 | **已完成** | 模块已注册至根 POM，`spring-boot-maven-plugin` repackage 成功，38 个类编译并运行 2/2 测试通过。 |
| **A** (A2A 传输协议) | 实现标准 A2A v1.0 JSON-RPC 端点（`/internal/a2a/money` 及 `/a2a/money`），支持 `money.investigate.v1` / `money.start`、`money.case-status.v1` / `money.getCase`、`money.provide-input.v1`、`money.cancel-task.v1`，精确异常族匹配 | **已完成** | `MoneyA2aEndpoint` 与 `MoneyAgentClient` 联调测试通过，无模糊贪婪匹配。 |
| **C** (客服接入闭环) | 客服三类资金意图与槽位解析，`master-planner-v3.xml.st` 模板覆盖，意图发布种子隔离，可靠本地 Dispatch 登记与异步 Binding，固定资金事实回复与防推断保护 | **已完成** | `mall-agent-service` 117 个单元与集成测试全部通过（含 `CustomerMoneyBridgeServiceTest` 5 个专项测试）。 |
| **O** (主动调查/工作台) | M4 领域 Outbox、主动扫描、财务工作台与影子评测 | 规划中 | 待当前闭环充分运行后展开。 |
| **W** (业务受控修复) | M5 业务命令一致性修复、财务人工审批流与受控补确认 | 规划中 | 严格遵循只读调查边界，第一阶段不开放自动资金写操作。 |

---

## 2. 数据库状态说明（严格区分）

- **SQL 脚本编写状态**：**已编写完成**，位于 `doc/sql/agent/moneyerrorAgent/`：
  - `money_error_v1_db_money_agent_core.sql`（包含 `db_money_agent` 核心 15 张表：`t_money_case`, `t_money_task`, `t_money_request`, `t_money_grant`, `t_money_task_access`, `t_money_task_input`, `t_money_snapshot`, `t_money_report`, `t_money_projection`, `t_money_input_request`, `t_money_event`, `t_money_tool_call`, `t_money_action_proposal`, `t_money_source_inbox`, `t_money_outbox`）
  - `money_error_v1_customer_money_intents.sql`（包含 `db_agent` 3 类资金意图与示例种子，默认全为 `DISABLED`）
  - `money_error_v1_db_agent_money_bridge.sql`（包含 `db_agent` 6 张桥接表：`t_agent_money_binding`, `t_agent_money_dispatch`, `t_agent_money_delivery`, `t_agent_money_card`, `t_agent_money_notice_inbox`, `t_agent_money_staff_note`）
  - `money_error_v1_db_order_money_query.sql`（包含 `db_order` 渠道观察与 Outbox 表）
  - `money_error_v1_db_user_fund_business_ref.sql`（包含 `db_user` 业务引用与 Outbox 表）
  - `money_error_verify.sql`（核对与校验脚本）
- **隔离测试数据库状态**：**已验证**。
  - 各服务单元测试均在隔离环境中验证了字段映射、时间 UTC 转换与唯一键约束。
- **业务生产环境数据库状态**：**已由用户人工执行到位**。
  - 用户确认本地数据库 SQL 文件已全部执行入库。

---

## 3. R 批次修复详细清单（审查 R01—R12）

| 审查编号 | 涉及组件 | 修复与落地代码 | 验证结论 |
| :---: | :--- | :--- | :--- |
| **R01** | `MoneySecuritySupport`<br>`MoneyOrderQueryDubboServiceImpl`<br>`MoneyFundQueryDubboServiceImpl`<br>`MoneySubjectResolver` | 分离三类独立票据密钥（SERVICE_SECRET, DELEGATION_SECRET, SCOPE_SECRET）；新增 `verifyEntryAccess` 强制校验 issuer、audience、action、grant、expiry；Order 与 User 所有接口入口强制拦截非法调用；买家主体严格核验本人。 | 伪造身份、越权访问、空票据与超范围请求均拒绝并抛出 `ForbiddenException`。 |
| **R02** | `MoneyContractAndSecurityTest` | 建立 6 项安全契约测试，覆盖服务身份、委托授权、范围证明伪造检测与有效期时效。 | 6/6 通过。 |
| **R03** | `MoneyChannelObservationService` | 移除 profile 缺失时的 `defaultFor` 猜测 fallback；原快照不可解析时明确返回 `PROFILE_UNRESOLVED` 且立即终止渠道调用（调用次数为 0）。 | 渠道未被虚假商户配置调用。 |
| **R04** | `MoneyChannelObservationService`<br>`ChannelObservation` | 严格区分 existence（EXISTS/NOT_FOUND/UNKNOWN）、请求结果与业务状态；渠道缺状态保持 UNKNOWN，不自动默认 SUCCESS；商户与币种比对严格取外部响应真实字段，无 response 时保持 UNVERIFIED。 | 9 类渠道场景测试 100% 匹配。 |
| **R05** | `MoneyFundQueryDubboServiceImpl`<br>`FundTransactionMapper` | 从 Order 已授权的 refs 构造确定性历史幂等键（`FUND_FREEZE:`, `FUND_REFUND:`, `FUND_SUSPENSE_CREDIT:`, `FUND_SUSPENSE_REFUND:`, `FUND_REFUND_CHANNEL:`）；支持查询 `orderNo=null` 历史挂账与退款；未关联记录显式输出 `LEGACY_DERIVED`。 | 历史挂账可完整追溯，查询过程零业务写操作。 |
| **R06** | `MoneyOrderQueryDubboServiceImpl` | 引入权威规范事实指纹计算：对 paymentOrder, attempts, refunds, events, diffs, orchTask 计算规范 SHA-256 哈希，任何退款状态改变、金额变化、编排推进均反映在指纹中。 | 指纹变动与事实变动严格一致。 |
| **R07** | `MoneyOrderQueryDubboServiceImpl` | 实现 `CUR:attemptId:refundId:eventId:diffId` 复合游标；每个集合查询 `limit + 1`；只有当所有集合均读取完毕且无截断时才标识 `completed=true`。 | 避免漏读和虚假 COMPLETE。 |
| **R08** | `MoneyFundQueryDubboServiceImpl` | 坚守只读边界：彻底移除 `getSnapshot` 内部的 `fundService.backfillLegacyReferencesIfMissing` 写事务；将历史关联回填改为只读派生映射，仅在独立管理任务中受控执行。 | `getSnapshot` 保持纯读，零写事务。 |
| **R09** | `TradeOrchestrationCommandService`<br>`FundService` | 渠道退款编排返回真实退款受理流水号 `refundNo` 并透传至资金服务；`FundService` 增加 `checkAndBackfillRefConflict` 保证幂等重放时安全补齐引用且决不发生二次动账。 | 解决假编号 `RF_ + orderNo` 缺陷，幂等与引用强一致。 |
| **R10** | `MoneyReconEvidenceService` | 严格按 `diff -> run -> task / file` 回溯关联；窗口未闭合不轻信对账结论；条目类型按渠道条目的 `itemType` 进行精准识别，纠正 G08 退款单边账缺陷。 | 单边退款与未闭合窗口回溯正确。 |
| **R11** | 全查询与观察组件 | 修复时区转换：将本地历史 `LocalDateTime` 按 `Asia/Shanghai` 转换为真实 UTC `Instant` ISO-8601 字符串；新建审计记录一律采用 `LocalDateTime.now(ZoneOffset.UTC)`。 | 消除跨时区 8 小时偏差。 |
| **R12** | `MoneyChannelObservationService` | 审计持久化强一致：仅当本地数据库审计写入成功后才返回 `AUDIT:` 引用；审计失败时显式返回 `evidenceRef=null` 与相应错误码，不返回虚假引用。 | 审计引用百分之百可信。 |

---

## 4. S 批次：独立微服务架构落地 (`mall-money-agent-service`)

### 4.1 工程与配置规范
- **服务坐标**：`com.example:mall-money-agent-service:0.0.1-SNAPSHOT`，位于根目录 `/Users/yu/IdeaProjects/mall-parent/mall-money-agent-service`。
- **模块依赖**：仅依赖 `mall-api` 及第三方 Spring Boot 3.5.6 / Dubbo 3.3.0 / MyBatis-Plus 运行库，**零依赖 `mall-order-service`、`mall-user-service` 或 `mall-agent-service` 的服务实现 JAR**。
- **配置与端口**：默认端口 `8086`（可由 `MONEY_AGENT_PORT` 动态覆盖）；单一数据源连接 `db_money_agent`，JDBC 时区显式配置为 `UTC`。
- **打包能力**：配置 `spring-boot-maven-plugin` repackage 目标，构建出支持独立 `java -jar` 部署的可执行 Jar 包。

### 4.2 15 张核心表实体与持久层
完整映射 `money_error_v1_db_money_agent_core.sql` 中的 15 张表：
1. `MoneyCaseEntity` / `MoneyCaseMapper` (`t_money_case`)
2. `MoneyTaskEntity` / `MoneyTaskMapper` (`t_money_task`)
3. `MoneyRequestEntity` / `MoneyRequestMapper` (`t_money_request`)
4. `MoneyGrantEntity` / `MoneyGrantMapper` (`t_money_grant`)
5. `MoneyTaskAccessEntity` / `MoneyTaskAccessMapper` (`t_money_task_access`)
6. `MoneyTaskInputEntity` / `MoneyTaskInputMapper` (`t_money_task_input`)
7. `MoneySnapshotEntity` / `MoneySnapshotMapper` (`t_money_snapshot`)
8. `MoneyReportEntity` / `MoneyReportMapper` (`t_money_report`)
9. `MoneyProjectionEntity` / `MoneyProjectionMapper` (`t_money_projection`)
10. `MoneyInputRequestEntity` / `MoneyInputRequestMapper` (`t_money_input_request`)
11. `MoneyEventEntity` / `MoneyEventMapper` (`t_money_event`)
12. `MoneyToolCallEntity` / `MoneyToolCallMapper` (`t_money_tool_call`)
13. `MoneyActionProposalEntity` / `MoneyActionProposalMapper` (`t_money_action_proposal`)
14. `MoneySourceInboxEntity` / `MoneySourceInboxMapper` (`t_money_source_inbox`)
15. `MoneyOutboxEntity` / `MoneyOutboxMapper` (`t_money_outbox`)

### 4.3 核心业务应用服务 (`application`)
- **`MoneyCaseService`**：
  - 案件与任务采用统一前缀命名：`MC...` 与 `MT...`；
  - 原子活跃案件去重：以 `SHA-256(scopeKey:problemFamily:targetRef)` 为唯一去重键，复用已有 `OPEN` 状态案件；
  - 严格权限归属：买家发起的调查不自动赋予财务负责人身份（`ownerStaffId=null`），仅管理后台财务角色可指派负责人；
  - 事务内同步发布生命周期事件（`t_money_event`）与面向受众的投影快照（`t_money_projection`）；
  - 支持 `closeCase` 释放去重键并记录结案原因。

### 4.4 调查 Graph 与规则引擎 (`investigation`)
- **`MoneyInvestigationGraph`**：
  - 构造真实内部服务间防伪凭据，跨服务消费 Order 与 User 的只读 Dubbo Provider；
  - 按照问题族（`PAYMENT_CONFIRMATION`, `REFUND_STATUS` 等）开展对象消解、订单快照读取、外部渠道观察与资金流水核对；
  - 事实裁决：精准识别掉单（渠道已扣款但平台待支付 → `NEEDS_REVIEW`，提示补确认）、未扣款（渠道不存在或已关闭 → `VERIFIED_NORMAL`）、退款出账（渠道已退款 → `VERIFIED_RESOLVED`）、证据缺失（`INCONCLUSIVE`）；
  - 装配不可变调查报告（`MoneyReportEntity`）并持久化，记录事实指纹与模型规则版本。

### 4.5 管理端 REST 控制台 (`api`)
- **`MoneyCaseAdminController`**：
  - 暴露路径 `/api/admin/money-agent/cases`，支持多维度分页筛选；
  - 暴露 `/cases/{caseNo}` 内部调查详情查询；
  - 暴露 `/cases/investigate` 财务受限主动建案；
  - 暴露 `/cases/{caseNo}/close` 结案操作。

---

## 5. A 批次：A2A 传输协议端点 (`api`)

- **端点注册**：`MoneyA2aEndpoint` 挂载于 `/internal/a2a/money` 及 `/a2a/money`。
- **协议方法支持**：
  - `money.investigate.v1` / `money.start`：受理调查请求，返回 taskNo、caseNo、受理状态及已有调查结论；
  - `money.case-status.v1` / `money.getCase`：快速查询当前案件最新视图与受众可见报告；
  - `money.provide-input.v1`：补充调查材料接入点；
  - `money.cancel-task.v1`：受控取消调查接入点。
- **异常族精确匹配**：
  - 杜绝 `contains("PAYMENT")` 覆盖 `EXCESS_PAYMENT` 的贪婪匹配缺陷；
  - 严格匹配 `PAYMENT_CONFIRMATION`、`EXCESS_PAYMENT`、`REFUND_STATUS`、`REFUND_CONFLICT`、`SUSPENSE_CLEARING`、`SETTLEMENT_DELAY`、`DISPUTE_INVESTIGATION`。

---

## 6. C 批次：客服 Agent 对接与闭环 (`mall-agent-service`)

### 6.1 意图、槽位与规划器模板
- **`IntentCatalog`**：
  - 注册 `PAYMENT_ANOMALY`（必填 `orderNo`，选填 `attemptNo`，路由至 `MONEY_BRIDGE`）；
  - 注册 `REFUND_ANOMALY`（必填 `orderNo`，选填 `refundNo`、`moneyCaseNo`，路由至 `MONEY_BRIDGE`）；
  - 注册 `MONEY_CASE_PROGRESS`（必填 `moneyCaseNo`，选填 `orderNo`，路由至 `MONEY_BRIDGE`）。
- **`master-planner-v3.xml.st`**：
  - 将 3 类新资金意图完整纳入主控规划器意图库与槽位契约定义，满足 `CustomerMasterTest` 全覆盖断言。
- **意图发布隔离**：
  - `IntentReleaseSnapshotTest` 遵守数据库种子定义（资金意图种子状态为 `DISABLED`），测试夹具正确隔离未发布意图，确保历史 13 个 L2 意图快照严格不受干扰。

### 6.2 客户端与可靠桥接服务
- **`MoneyAgentClient`**：
  - 封装 REST / JSON-RPC 调用，带 5 秒连接与读取超时；
  - 完整解析 `MoneyStartResponse`（caseNo, taskNo, caseState, taskState, outcome, isNew）与 `MoneyCaseResponse`。
- **`CustomerMoneyBridgeService`**：
  - **先本地落库 Dispatch 再发起远程调用**：持久化写入 `t_agent_money_dispatch`（包含 dispatchNo, requestId, requestHash, payloadJson, grantRef 等），远程失败时更新状态为 `FAILED`，并返回优雅排队提示；
  - **受理成功后绑定**：写入 `t_agent_money_binding`（用户级活跃案件绑定，支持多会话漫游）；
  - **事实与推断严格分离**：
    - 渠道退款成功仅表述为“已核实外部支付/退款渠道出账成功（注：买家端实际到账时效以发卡行清算入账为准，通常需 1-3 个工作日）”，绝不虚假宣传“已妥善到账”；
    - 掉单表述为“已检测到渠道流水与平台订单存在单边差异，调查取证材料已完成归档，正在等待财务专员人工复核确认”；
  - 进度查询支持优先匹配明确案件号，未指定时结合订单号消解。
- **单元测试**：
  - `CustomerMoneyBridgeServiceTest` 包含 5 个专项测试用例，覆盖缺少订单号拦截、已有活跃案件防重复建案、新建案件 Dispatch+Binding 写入、远程异常优雅降级、进度查询友好文案转换。

---

## 7. 全反应堆构建与测试结果（9 模块完整验证）

运行全量 Maven 测试验证：
```bash
mvn test
```

### 构建摘要日志：
```text
[INFO] Reactor Summary for mall-parent 0.0.1-SNAPSHOT:
[INFO] 
[INFO] mall-parent ........................................ SUCCESS [  0.001 s]
[INFO] Archetype - mall-api ............................... SUCCESS [  1.310 s]
[INFO] mall-gateway-service ............................... SUCCESS [  1.280 s]
[INFO] Archetype - mall-user-service ...................... SUCCESS [  1.500 s]
[INFO] Archetype - mall-item-service ...................... SUCCESS [  1.113 s]
[INFO] Archetype - mall-order-service ..................... SUCCESS [  6.790 s]
[INFO] mall-risk-service .................................. SUCCESS [  1.878 s]
[INFO] mall-agent-service ................................. SUCCESS [  6.091 s]
[INFO] mall-money-agent-service ........................... SUCCESS [  3.027 s]
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] Total time:  23.168 s
```

**测试数据统计**：
- `mall-api`: 16/16 tests passed (0 failures)
- `mall-gateway-service`: 30/30 tests passed (0 failures)
- `mall-user-service`: 28/28 tests passed (0 failures)
- `mall-item-service`: 全部测试 passed (0 failures)
- `mall-order-service`: 82/82 tests passed (0 failures)
- `mall-risk-service`: 全部测试 passed (0 failures)
- `mall-agent-service`: 117/117 tests passed (0 failures, 1 skipped)
- `mall-money-agent-service`: 2/2 tests passed (0 failures)
- **总状态**：**全模块 100% 编译通过，所有既有和新增用例 0 失败、0 错误、0 回归破损**。
