# Money Agent 实现审查

审查日期：2026-10-31。对象：当前工作区源码、SQL、测试与实施进度记录，包含尚未提交的改动。依据：[主体设计](development_design.md)、[数据契约](data_contracts_and_schema.md)、[客服接入](customer_agent_integration.md)、[实施与验收](implementation_and_acceptance.md)。本次仅审查并记录结果，没有修复业务实现或执行数据库迁移。

**验收口径：支付、银行及退款渠道允许使用项目现有 Mock 实现，不要求申请或接入真实金融机构接口。Mock 渠道本身不构成缺陷或未完成项。** 下文要求验证的是平台自身的身份权限、业务查询、事实语义、持久化与协作流程；数据库集成测试可以使用隔离测试库，渠道继续使用 Mock。

## 1. 结论与阶段状态

**没有完整实现。当前交付主要是 M0/M1 的公共契约和查询代码，而且存在阻止这两个阶段验收的权限与事实准确性问题。M2/M3 的独立调查和客服协作闭环尚不存在。**

| 阶段 | 当前实际内容 | 审查结论 |
| --- | --- | --- |
| M0 | 29 个公共类/接口/枚举、票据工具、管理端网关路由 | 部分实现；凭据校验没有接入查询入口，默认签名密钥与授权范围不满足设计 |
| M1 | Order 查询、渠道观察、对账回溯、User 资金查询、引用实体与写入 | 部分实现；存在越权入口、虚假匹配、历史漏查、分页和指纹缺陷；缺隔离 MySQL 的实际执行验收，支付渠道可保持 Mock |
| M2 | 已有建表 SQL | 未实现运行时；根 POM 无 mall-money-agent-service，目录也不存在，无案件/任务服务、Graph、Worker、报告或 A2A 服务 |
| M3 | 已有客服桥接 SQL 和默认禁用的意图种子 | 未实现；客服 IntentCatalog 无三类新意图，未发现 MoneyAgentClient、MONEY_BRIDGE、dispatch/sync Worker、资金卡片或前端接入 |
| M4 | 已有领域 Inbox/Outbox 表脚本 | 未实现事件发布、消费、主动调查、运营工作台或影子评测 |
| M5 | 已有修复命令表脚本 | 未实现审批、受控命令和执行复查；按分期可继续保持未启用 |
| 数据库 | 7 个 SQL 文件 | 文件存在，不等于表已部署或业务功能可运行；本次未核验任何业务环境数据库 |

如果此前开发指令仅覆盖 M0/M1，M2—M5 未实现属于该次任务的范围边界；但仍不能将 M0/M1 的现状判为满足设计验收。不要用“测试全通过”替代功能与权限覆盖检查。

## 2. 主要发现

### R01 · P1：查询入口未执行服务身份、委托和对象范围校验

- 位置：`MoneyOrderQueryDubboServiceImpl.java:64–77`、`MoneySubjectResolver.java:105–118,158–161`、`MoneyFundQueryDubboServiceImpl.java:39–55`。
- Order 的 getSnapshot 直接从调用方 scopeKey 提取订单号查询，没有使用 access；observeChannel/getReconEvidence 也没有接入身份验证。resolveSubject 在 access=null 或主体 ID 缺失时跳过买家校验，自报 ADMIN/FINANCE/CUSTOMER_STAFF/SYSTEM 可进入财务视图。
- User 只验证 scope proof，本人校验仅在自报角色为 BUYER 且双方 ID 都非空时发生；access=null 或改填其他角色即可绕过主体绑定。
- `verifyServiceIdentity/verifyDelegation` 目前只有工具和测试，没有生产入口调用。Gateway 的 HTTP 管理路径限制不能替代 Dubbo Provider 授权。
- 影响：能调用这些 Dubbo 接口的调用方可读取指定订单/渠道/对账事实，或用未绑定原主体的 scope proof 获取资金数据；不能声称已形成可信 A2A 查询底座。
- 动态复核：使用内存 Mapper 替身调用 resolveSubject(null, ...)、getSnapshot(null, ...) 均成功；User 使用有效 scope proof、空 access 也返回资金事务。
- 修复要求：统一入口校验器，缺少凭据或主体时拒绝；由验签后的 claims 决定角色/服务/动作/范围，对每个查询对象重新校验归属。不能依赖客户端先调用 resolveSubject。

### R02 · P1：票据使用公开默认密钥，未落实签发者/接收者隔离

- 位置：`MoneySecuritySupport.java:21–24,50–56,62–90,96–140,221–223`。
- 环境变量未设置时静默使用源码中的固定签名密钥。服务身份、委托、范围证明共用同一密钥；验签没有检查 audience，委托票据也没有独立 issuer/audience、允许动作或 grant 绑定。
- 影响：默认部署下签名不构成可信身份；单一共享密钥持有者还能冒充 Order 签发范围证明。补上 R01 的方法调用仍不足以完成 M0。
- 修复要求：缺少必需密钥配置时拒绝启动，区分签发与验签权限，校验 issuer/audience/action/grant/有效期；补充错误受众、错误签发者、撤权与角色变化用例。

### R03 · P1：原 profile 缺失时仍查询默认渠道，违背明确的取证边界

- 位置：`MoneyChannelObservationService.java:231–245`。
- 无快照或快照解析失败后，只要 paymentMethod 合法就调用 ChannelProfileSnapshot.defaultFor。进度文档所述“失败时报 PROFILE_UNRESOLVED、不篡改默认商户”与代码不一致。
- 影响：历史配置无法恢复时可能查询当前默认商户/账户，而不是交易发生时的渠道，得到无记录或属于错误配置的观察。
- 动态复核：未设置 profileSnapshot 的支付尝试仍触发 queryRefund。
- 修复要求：缺失/不一致时返回 PROFILE_UNRESOLVED 并停止渠道调用；若允许恢复，必须来自可验证的原配置版本，不能靠 paymentMethod 选择默认值。

### R04 · P1：渠道结果会被补成未经核实的成功或匹配事实

- 位置：`MoneyChannelObservationService.java:92–100,163–171`。
- 支付记录 exists=true 但 status 缺失时，rawStatus 默认 SUCCESS；currency 缺失时借用本地币种，可能把未知币种标为匹配。
- 退款 exists 使用 `success || status != null`，现有 Mock 的 NOT_FOUND 回包也有 status，因此会得到 exists=true。退款 merchantMatched/currencyMatched 更是直接赋值 true；当前 ChannelRefundResult 并未提供足够字段支持这两个结论。
- 动态复核：NOT_FOUND 得到 exists=true；没有渠道商户/币种证明时两个匹配标志为 true；缺支付状态时输出 SUCCESS。
- 修复要求：显式区分存在、调用成功、业务状态与身份匹配；缺字段保留 UNKNOWN/未验证。扩展适配器证据字段或降低质量标志，不能把请求中的参数当作响应证明。

### R05 · P1：历史挂账/退款事务会漏查，并被报告为完整覆盖

- 位置：`MoneyFundQueryDubboServiceImpl.java:59–85,148–153`。
- 查询只通过 order_no 和新建的 t_fund_business_ref 找事务。项目既有挂账/挂账退款事务没有 orderNo；升级前也没有引用表记录。代码没有根据已验证 attempt/refund 构造精确历史幂等键查询。
- backfillLegacyReferencesIfMissing 只对已按 orderNo 找到的事务执行，不能解决这些本来就没有 orderNo 的记录。
- 找不到任何事务/引用时，仍返回 USER_LEDGER=OK、BUSINESS_REF=OK、complete=true。
- 影响：实际存在的挂账/退款流水可能在调查中消失，后续 Agent 可能误判资金步骤缺失；不能把此结果作为“没有额外款项”的证据。
- 修复要求：在授权引用范围内增加精确历史幂等键查询，区分未发生与 LEGACY_UNLINKED；不以金额近似匹配。增加升级前无 orderNo/无引用的真实数据用例。

### R06 · P1：快照指纹不能反映资金事实变化

- 位置：`MoneyOrderQueryDubboServiceImpl.java:218–220`。
- 指纹只包含订单号、paymentOrder.status、attempt 数量和 refund 数量。单笔退款 PROCESSING→SUCCEEDED、金额/币种/版本变化、金融事件和业务编排变化都可能保持指纹不变。
- 动态复核：保持集合数量和支付状态，改变退款状态后，两份快照指纹完全相同。
- 影响：后续按事实指纹去重、重调查或校验报告时会接受过期结果，违背设计的证据更新机制。
- 修复要求：对确定排序的关键事实、真实版本/字段 hash、coverage 和各来源状态进行规范化散列；同一事实不变应稳定，有实质变化必须变化。

### R07 · P1：分页无法续读，金融事件/差异截断还可能标为 COMPLETE

- 位置：`MoneyOrderQueryDubboServiceImpl.java:73–111,211–216`。
- 入参有 cursor，查询却从不使用；attempt/refund/event 都只取第一页，返回 nextCursor 为 null。记录达到 limit 时，即使表示 TRUNCATED，也没有办法继续读完。
- hasMore 只检查 attempt/refund，忽略 financialEvents 和固定 LIMIT 20 的 diffs。这两类被截断时仍可能 complete=true；来源 coverage 也未覆盖这些集合。
- 影响：同一订单存在较多金融事件或差异时，证据集合不完整却被消费方当作完整快照。
- 修复要求：各集合采用稳定游标与 limit+1，完整取数或明确返回分页/截断信息；所有必需源都进入 coverage。不能通过重复读取同一页完成调查。

### R08 · P2：资金只读查询会写业务引用，历史证据与查询路径混在一起

- 位置：`MoneyFundQueryDubboServiceImpl.java:67`、`FundService.java:369–389`。
- getSnapshot 调用 backfillLegacyReferencesIfMissing，后者直接插入 t_fund_business_ref。这里没有独立回填任务、完整业务关联校验或受控事务边界。
- 动态复核：一次名为 getSnapshot 的查询触发了 businessRefMapper.insert。
- 影响：查询会改变后续调查证据和关联结果，且引用写入失败可使只读查询失败；这与允许写调查审计记录不同，不涉及实际余额扣减但仍违反 M1 只读边界。
- 修复要求：查询时只返回经过验证的派生关联及质量；持久回填交给单独、可审计的回填/业务写路径。

### R09 · P2：新引用写入尚未解决真实退款号与幂等重放的关联问题

- 位置：`FundService.java:95–108,302–315,332–347,392–399`；现有调用方 `TradeOrchestrationCommandService.java:390–405`。
- Order 仍把 `RF_ + orderNo` 作为 refundNo 传给 User，实际渠道退款号由 processChannelRefund 另行生成。新增引用写入直接把这个构造号记为 TRANSACTION_BIND，不能证明关联到真实退款单。
- execute 在幂等命中时提前返回；比较参数不含 attemptNo/refundNo，也不校验或补齐引用。因此同 key 更换关联对象仍可能得到旧成功回执，升级前事务重放也不会补齐引用。
- 修复要求：传递真实业务退款号与 operationId；幂等重放验证引用兼容性，并在同一本地事务内安全补齐。不能把新表有数据等同于关联可信。

### R10 · P2：对账取证未验证文件/窗口，渠道单边退款仍可能误分类

- 位置：`MoneyReconEvidenceService.java:47–51,69–96`。
- windowClosed 仅依据 Run 执行状态，不检查业务日窗口、账单文件与解析完整性；billDate 从 snapshotCutoff/lastSeenAt 推导，重跑旧业务日时可能显示运行日。
- fallback 渠道条目查询只按 outTradeNo/refundNo 并 LIMIT 1，未限定 run 的 fileId、profile、商户或账单版本，重复出账/重跑时可能命中旧条目。
- resolvedItemType 没有读取 channelItem.itemType。只有渠道侧的 `REF_SUSPENSE_...` 退款，若没有 localItem，既不以 RF_ 开头也不包含 REFUND，会被默认为 PAYMENT。
- 修复要求：从 diff→run→file/task 复原来源、窗口和业务日，以原始条目类型分类；关联不唯一时报告缺口，不任取一行。

### R11 · P2：历史本地时间被直接标为 UTC，新审计时间也未显式转换

- 位置：`MoneyOrderQueryDubboServiceImpl.java:264–266`、`MoneyFundQueryDubboServiceImpl.java:164–166`、`MoneyChannelObservationService.java:222–223`。
- 两个来源服务的 JDBC 配置均为 Asia/Shanghai，现有业务使用 LocalDateTime 写入。新增 formatIso 直接 atOffset(UTC)，没有转换时刻；新观察审计表约定 UTC，却写 LocalDateTime.now()。
- 影响：在项目当前本地时区配置下可能出现 8 小时偏移，影响回调先后、退款时效和对账窗口解释。
- 修复要求：旧字段根据来源实际时区转 Instant，新字段明确 UTC，补跨日/业务日边界测试。

### R12 · P2：审计写失败后仍返回可追溯的成功观察

- 位置：`MoneyChannelObservationService.java:125–133,196–203,206–228`。
- evidenceRef 先填 AUDIT:obsNo；recordAudit 失败只写日志，外层仍返回该引用与成功观察。
- 影响：报告可以引用并不存在的持久证据，重启后无法重放与审计。
- 修复要求：审计持久化成功后才发出对应证据引用；失败时显式标为不可持久验证或失败，不伪造已落库的引用。

## 3. 测试与复核证据

本次实际执行：

```bash
mvn -o -pl mall-api,mall-order-service,mall-user-service -am \
  -Dtest=MoneyContractAndSecurityTest,MoneyOrderQueryIntegrationTest,MoneyFundQueryDubboServiceImplTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：BUILD SUCCESS；API 5、Order 4、User 3，共 12 个测试通过，0 失败、0 错误。没有把本次结果描述为全工程重跑。

使用 Mock 渠道测试支付成功、失败、处理中、无记录和通知丢失都是有效方案。本次没有要求真实银行测试。以下关于 Mockito 的局限针对“Mapper/业务服务也全部被替换后，无法证明数据库映射与事务实际行为”，并非否定 Mock 渠道或单元测试的价值。

但是：

- Order 所谓 IntegrationTest 在 setUp 中将全部 Mapper 与渠道替换为 Mockito；User 测试将 FundService 也替换为 Mock。它们没有执行真实 SQL/事务，因此不会捕获 R08 的读中写，也不能证明 MySQL 唯一键、回填、并发或只读性。
- 多个通过用例传入空 serviceIdentityProof/delegationProof 并预期成功，恰好掩盖 R01；渠道用例缺 profileSnapshot 却预期成功，也固化了 R03。
- 安全工具测试覆盖正常签发与部分篡改，不代表服务入口实际使用了校验，也没有完整验证接收者/权限撤回链。

额外在 `/tmp/money-agent-review/ReviewProbes.java` 创建一次性 Java 探针，调用当前已编译的真实服务逻辑，Mapper/渠道为内存替身，无数据库或网络交互。结果记录在 `/tmp/money-agent-review-probes.log`，复现了：

1. 空身份解析订单成功。
2. 空身份查询订单快照成功。
3. 退款状态变化后指纹不变。
4. 原 profile 缺失仍调用默认渠道。
5. NOT_FOUND 退款标记 exists=true。
6. 缺商户/币种证据仍标记匹配。
7. 缺支付状态被解释为 SUCCESS。
8. User 空身份配有效 scope proof 返回资金事实。
9. 资金查询执行引用 INSERT。
10. 空事务/引用集合被标记 complete=true（历史漏查原因另由源码路径确认）。

这些探针证明应用分支行为，不替代真实数据库、Dubbo 网络部署或生产风险验证。本次没有连接业务数据库，不能据此判定部署迁移状态。

## 4. 建议执行顺序

1. 将 M0/M1 的验收状态视为“部分实现、需修复”，先处理 R01/R02 的身份和授权边界。
2. 修复 R03—R07，保证渠道身份、结果语义、历史关联、指纹和覆盖度可以作为调查事实使用。
3. 修复 R08—R12 的只读边界、关联可靠性、对账来源、时间和审计持久性。
4. 新增针对上述失败路径的回归测试，并按设计运行隔离 MySQL 集成测试：迁移与重放、映射、精确历史关联、唯一键、并发和事务回滚。支付、银行与退款渠道仍可使用 Mock，无需接入真实金融机构。
5. 达到 M0/M1 验收门槛后开发 M2 独立服务，再开发 M3 客服双向协作。M4/M5 继续遵守分期，不为补齐进度而提前启用资金写操作。

## 5. 源码入口

| 内容 | 文件 |
| --- | --- |
| 原进度记录 | [implementation_progress.md](implementation_progress.md) |
| 模块清单 | [根 POM](../../../pom.xml) |
| 身份票据 | [MoneySecuritySupport](../../../mall-api/src/main/java/api/agent/money/MoneySecuritySupport.java) |
| 对象解析 | [MoneySubjectResolver](../../../mall-order-service/src/main/java/com/example/item/money/MoneySubjectResolver.java) |
| 订单查询 | [MoneyOrderQueryDubboServiceImpl](../../../mall-order-service/src/main/java/com/example/item/money/MoneyOrderQueryDubboServiceImpl.java) |
| 渠道观察 | [MoneyChannelObservationService](../../../mall-order-service/src/main/java/com/example/item/money/MoneyChannelObservationService.java) |
| 对账取证 | [MoneyReconEvidenceService](../../../mall-order-service/src/main/java/com/example/item/money/MoneyReconEvidenceService.java) |
| 资金查询 | [MoneyFundQueryDubboServiceImpl](../../../mall-user-service/src/main/java/com/example/user/provider/MoneyFundQueryDubboServiceImpl.java) |
| 资金引用写入 | [FundService](../../../mall-user-service/src/main/java/com/example/user/service/FundService.java) |
| 退款编排调用 | [TradeOrchestrationCommandService](../../../mall-order-service/src/main/java/com/example/item/service/TradeOrchestrationCommandService.java) |
| 客服意图 | [IntentCatalog](../../../mall-agent-service/src/main/java/com/example/agent/customer/IntentCatalog.java) |
| Order 专项测试 | [MoneyOrderQueryIntegrationTest](../../../mall-order-service/src/test/java/com/example/item/money/MoneyOrderQueryIntegrationTest.java) |
| User 专项测试 | [MoneyFundQueryDubboServiceImplTest](../../../mall-user-service/src/test/java/com/example/user/service/MoneyFundQueryDubboServiceImplTest.java) |
