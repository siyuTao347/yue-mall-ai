# 资金异常调查：实施拆分、验收与源码依据

> 与[主体设计](development_design.md)、[数据设计](data_contracts_and_schema.md)、[客服接入](customer_agent_integration.md)组成同一份开发设计。数据库脚本已生成但未执行，以下业务开发和运行测试仍待实施。

## 1. 开发顺序与上线门槛

| 批次 | 交付目标 | 依赖/验收门槛 |
| --- | --- | --- |
| M0：契约与基础核对 | 确认 DB schema、当前支付路径、DTO/schema、对象授权、纯查询边界 | 每个 fact 字段有明确来源；无权限/无记录/不可用语义不同 |
| M1：源服务查询 | Order 支付/退款/差异快照、渠道观察；User 资金查询/引用 | 数据库集成测试证明只读工具无业务副作用；历史无法关联可显式表示 |
| M2：Money 独立运行 | 新模块/库、规则、Graph、Worker、报告、A2A 适配 | 不接客服也可由运营任务跑完；重复/重启/超时测试通过 |
| M3：客服闭环 | 意图/槽位、dispatch/binding、补充输入、消息和卡片 | 独立进程 A2A 联调通过；用户权限、转人工、断线恢复验收通过 |
| M4：主动调查与影子评测 | 领域 Outbox、候选扫描、运营工作台、基线比较 | 能证明实际调查材料价值，未出现错误成功陈述或越权 |
| M5：受控修复 | 持久化命令、严格补确认、审批、执行复查 | 主体 G03/G05/G07/G10 等相关一致性问题已修复并验证；否则保持只读 |

M3 不依赖 M5。不能为了能回答用户“钱去哪了”先开放自动退款；也不能只做一个聊天演示就跳过 M1。

数据库 SQL 已写入 `doc/sql/agent/moneyerrorAgent`，使用 `money_error_` 前缀，详见[文件清单与执行顺序](../../sql/agent/moneyerrorAgent/README.md)。实施时先核对已有脚本，按阶段验证和补充必要增量，不能把文件已生成当作数据库已迁移；各表所属数据库保持数据分册的约定。交给 Agent 工具执行时，使用[执行提示词](agent_execution_prompts.md)明确工作目录、设计依据、本次阶段和验收要求。

## 2. 后端逐文件工作项

以下“新增类”是准确的目标名称建议；现有文件见第 9 节源码链接。

### 2.1 公共契约与基础安全

| 位置 | 改动 | 验收产物 |
| --- | --- | --- |
| 根 `pom.xml` | 新增 `mall-money-agent-service` module；沿用现有 BOM | 模块可独立启动，依赖无循环 |
| `mall-api/api/agent/money` 新目录 | DTO、查询接口、错误码、有限枚举；不暴露 SDK/数据库实体 | JSON/Dubbo 往返契约测试 |
| 公共身份验证组件 | 服务身份、用户/运营委托、Order scope proof 校验 | 过期、错误 audience、篡改范围、错误 issuer 全部拒绝 |
| Gateway 配置 | `/api/admin/money-agent/**` 转发与管理员限制；内部 A2A 不走公开免登录路径 | 用户无法访问管理/内部入口 |

### 2.2 Order

| 类/位置 | 具体改动 |
| --- | --- |
| 新 `MoneySubjectResolver` | 按 ORDER/ATTEMPT/REFUND/DIFF 精确定位，校验对象关系与调用者范围 |
| 新 `MoneyOrderQueryDubboServiceImpl` | 聚合标准 DTO；分页完整性、查询预算、源错误与 fingerprint |
| 新 `MoneyChannelObservationService` | 解析原 profileSnapshot，调用 queryPayment/queryRefund，验证商户/币种/金额，保存观察审计 |
| 新 `MoneyReconEvidenceService` | 从 diff/run/match 回溯 itemType 与原条目，校验窗口/解析覆盖；避免错误 diff 标签直接驱动结论 |
| `PaymentAttemptMapper` 等 Mapper | 增加受限范围查询与游标，必要索引；不暴露通用 SQL 接口 |
| `PaymentApplicationService` | 分开退款受理/处理中/成功事件；后续退款操作稳定去重、原退款查询恢复 |
| `PaymentConfirmationService` | 金额、币种、商户、事件类型与状态严格校验；并发锁/唯一采用；挂账与退款步骤可靠恢复 |
| `TradeOrchestrationCommandService` | 使用真实 refundNo 与 operationId 关联资金；区分渠道未知与内部出账 |
| `ReconciliationRepairService` | 提取查询与修复，修复走命令台账；不要将管理动作当作只读工具 |
| 新 `MoneyDomainEventPublisher` | 业务事务 Outbox，与已有 Mock 通知 Outbox 分开 |
| 新 `MoneyRepairCommandService`（M5） | 仅允许审核后的动作，幂等、预期条件、执行状态、回执查询 |

主体现状 G04 的退款事件修正需要检查历史数据：保留原金融事件审计，使用纠正/补充记录与查询投影解释；不批量删除账务历史。退款失败路径在事务中写 FAILED 后抛异常也需测试，避免失败记录随事务回滚而被调查误判为“从未提交”。

### 2.3 User

| 类/位置 | 具体改动 |
| --- | --- |
| 新 `MoneyFundQueryDubboServiceImpl` | 验证 Order scope proof，仅返回目标关联资金事务和必要流水 |
| 新 `FundBusinessRef` / Mapper / Service | 原子记录 ORDER/ATTEMPT/REFUND/OPERATION 引用 |
| `FundService` / `FundDubboService` | 通过兼容的新重载/命令 DTO 传递真实关联；保留旧调用，禁止同一幂等事务绑定不同业务 |
| 回填 Job | 仅已验证精确键回填；无法关联留状态，不基于金额猜测 |

不要修改 `FundService` 的已有幂等键约束来迁就 Agent 随机生成的键。新的 Money operationId 与资金业务幂等键在业务服务内做明确映射。

### 2.4 Money 服务

| 子任务 | 实现要点 |
| --- | --- |
| App/POM/配置 | Spring Boot、MySQL、Dubbo consumers、Actuator、Graph 与独立模型配置；不复制客服整个应用 |
| Case/Task/Request/Access | 活动业务键去重、每案一个非终态任务、稳定回执、执行授权与受众访问分离 |
| Graph/Rules/Planner | 主体第 7 节规则及 MoneyStatusNormalizer；有限动作目录；不可重复无效调查；人工停止条件 |
| Snapshot/Report/Projection | 来源、coverage、指纹、受众、不可变报告与投影版本 |
| Worker/Recovery | lease/fencing/CAS、事务外远程执行、重启恢复、有限重试 |
| A2A Adapter | 身份、调查与 case-status 技能、任务/案件分别查询、补充/取消、受众过滤、终态不可重启 |
| InputService | 字段 schema、目标用户、版本、重复提交回执、超时处理 |
| Admin/Proposal | 岗位权限、负责人、审核条件、不可直接传任意命令 |
| MockModel/Replay | 不依赖真实模型的完整用例；回放时禁止外部副作用 |

### 2.5 客服后端

| 现有/新增类 | 工作项 |
| --- | --- |
| `IntentCatalog` | 增加 3 intent→MONEY_BRIDGE、required/optional slots |
| `CustomerSlotRegistry`（新） | 集中管理槽位、编号词法、显示名和实体集合 |
| `CustomerMaster` | 更新全部抽取/验证/恢复分支与 Mock；保留人工升级优先级 |
| `CustomerContextWindowBuilder` | moneyCases/verifiedReferences 有界输入 |
| `CustomerPromptRegistry` / `CustomerModels` | 注册 Master v4；带 Money 的回复走分段锁定与缓冲 |
| `CustomerRunner` | LOAD_CONTEXT 注入引用、桥接分支、局部失败、money/cards 保留、控制版本 |
| `CustomerToolPolicy` | 新桥接工具，不开放资金命令 |
| `CustomerOutputGuard` | Money 事实、金额、case-order、模板与版本校验 |
| `CustomerMoneyApplicationService`（新） | 本地提交/查询/输入/取消；用户范围与业务去重 |
| `CustomerMoneyDispatchWorker`（新） | 稳定请求、后台 A2A、accepted receipt 与绑定 |
| `CustomerMoneyTaskSyncWorker`（新） | 轮询与版本比较，产生有意义的投递任务 |
| `CustomerMoneyNoticeService`（新） | 受控主动通知 Inbox、Order 收件人解析 |
| `CustomerSystemMessageService`（新） | 系统角色、受众、消息/事件/回执原子写入 |
| `CustomerSessionAccessService`（新） | snapshot/sync/read/card 等统一访问校验 |
| `CustomerStore` | start/finish controlEpoch 精确校验、返回稳定消息关联、Money 缓冲结果先提交后推送 |
| `CardActionService` | 新动作严格委派、禁止未知动作默认成功，校验归属/版本/幂等 |
| `ConversationMessageStore` / 新 ViewAssembler | payload/cards 兼容读取、标准消息 DTO |
| `CustomerRealtimeController` | 授权、统一快照、资金 card-action handler、补拉边界 |

## 3. 前端逐文件工作项

| 文件/新组件 | 工作项 |
| --- | --- |
| `customerServiceApi.js` | 修正已核对的实时路径；新增 money API；提交/查询使用短请求，不复用 95 秒调查等待 |
| `customerRealtimeClient.js` | 向上保留 envelope 标识；去重与补拉协作，确认重连订阅 |
| `CustomerRealtimeContext.jsx` | messages/recentMessages 兼容；稳定消息 ID；保留 cards；按 eventSeq/revision 更新 |
| `CustomerServiceDialog.jsx` | 展示登记/受理/补证/复核区别，页面关闭不取消调查 |
| `RichMessageRenderer.jsx` | 注册 money_investigation/money_input，旧客户端 fallback |
| `MoneyInvestigationCard.jsx`（新） | case/order、四层可见状态、最后核实时间、允许动作 |
| `MoneyInputCard.jsx`（新） | 有界字段、版本、SUBMITTING 与 SUBMITTED、错误与重复点击 |
| `CustomerStaffWorkspace.jsx` | 资金案件摘要与内部便笺，人工选择公开，保持控制版本 |
| `ReconciliationConsole.jsx` | 从差异发起调查、显示已关联案件，不能反复建同案 |
| `moneyAgentApi.js` / `MoneyInvestigationConsole.jsx`（新） | 财务案例列表/证据/建议/审批/命令进度；无权字段不从后端返回 |
| `App.jsx` 或当前管理导航 | 增加管理入口和角色显示；真正权限仍在后端 |

UI 不显示“多 Agent 思考链”，只展示可理解的业务阶段、来源和下一步。

## 4. 源服务与调查规则测试矩阵

每个用例至少断言：事实来源、调查分类、允许动作、用户可见文本、是否有业务写入。不能只断言一个枚举值存在或 BigDecimal 不相等。

| ID | 场景 | 必须验证的结果 |
| --- | --- | --- |
| F01 | 正常单次支付 | 不建无意义调查；普通查询可回答 |
| F02 | Mock DROP_PAYMENT_NOTIFY，渠道成功平台未采用 | PAYMENT_CONFIRMATION_GAP；读取渠道和平台证据，不直接补单 |
| F03 | 同订单两个成功尝试 | 分开识别 ORDER_APPLIED 与额外款；不只读 successfulAttemptNo |
| F04 | 已关闭订单迟到支付 | 不复活订单；挂账/退回按独立事实检查 |
| F05 | 挂账金融事件存在、User 挂账事务缺失 | SUSPENSE_LEDGER_GAP，不以 attempt SUCCEEDED 宣称处理完成 |
| F06 | 退款 PROCESSING 但历史 successAt/eventStage=SUCCESS | 保留冲突，不能声明渠道成功或到账 |
| F07 | 渠道退款成功、内部资金步骤未知 | 分层报告，不把两层混成全流程完成 |
| F08 | 平台内部出账、渠道 FAILED/UNKNOWN | 不再次发起退款，建议查原操作 |
| F09 | 退款失败事务回滚后本地无记录 | 不能凭无记录证明未发生外部操作；转核查原操作/渠道 |
| F10 | 历史 FundTransaction 无 orderNo | 精确幂等键/引用关联；无法关联返回 LEGACY_UNLINKED |
| F11 | diff.orderNo 为空、退款被标 *_PAYMENT | 回溯 itemType；不向用户暴露其他记录；不按标签补支付 |
| F12 | 同金额不同币种/商户 | 冲突，禁止补确认 |
| F13 | 账单未到可用时间/解析不完整 | RECON_WINDOW_OPEN，不判定资金丢失 |
| F14 | 超过一页支付/退款记录 | 分页完整读取或显式 TRUNCATED；禁止否定性结论 |
| F15 | legacy 支付无 attempt | 输出受限旧链路事实，不编造 PA 编号 |
| F16 | 原 profileSnapshot 缺失或不一致 | PROFILE_UNRESOLVED，不用当前默认商户查询错误账户 |
| F17 | 多笔退款占用/成功和大于原支付 | 明确风险 finding，无新增退款权限 |
| F18 | Channel query 超时/NOT_FOUND | 两者不同，不可用不能转换为不存在 |

F02 可利用当前 `MockChannelEngine` 的 `DROP_PAYMENT_NOTIFY`；金额异常可利用现有 `AMOUNT_MISMATCH`。当前代码未证明支持的退款延迟、响应丢失等 fault-plan 类型不得假称已有：在测试中用受控 stub 或新增 Mock 故障能力，并标明测试专用。

## 5. 客服与 A2A 集成验收

| ID | 场景 | 验收 |
| --- | --- | --- |
| C01 | 只给订单号投诉扣款 | 可信归属、受理回执、后台任务与用户卡片一致 |
| C02 | 只给 PA_ 或 RF_/REF_SUSPENSE_ 编号 | 受限解析到订单，不从字符串截取绕过授权 |
| C03 | “退款怎么申请” | 仍走 AFTER_SALE_GUIDE，未创建 Money Task |
| C04 | “调查有结果了吗”有两个活动案 | 要求选择，不误关联最新一笔 |
| C05 | 同轮两笔订单异常 | 各自 intent/task/事实隔离；共享预算不超限 |
| C06 | 金融问题 + 普通规则问题 | 局部受理/局部失败不使其他子任务失败 |
| C07 | L2 与 L3 对边界输入 | 评估付款状态/售后进度/退款异常/提现/人工升级混淆 |
| C08 | 新意图库只更新 MySQL 未发布 | 新功能不能被当成已上线；发布校验覆盖全意图库 |
| C09 | 远端成功但客户端首响应丢失 | 相同 requestId 重放恢复同 task；不重复调查/投递 |
| C10 | 提交补充信息后用户连点或网络重试 | 同输入版本只生效一次；同 key 不同内容冲突 |
| C11 | Task 终态后再补资料 | 新建关联 Task，旧 Task 不复活 |
| C12 | A2A 对端只读任务超时/服务重启 | 持久恢复与退避，客服可继续普通查询 |
| C13 | 钱包/平台出账事实被模型写成“已到账” | Guard 拒绝；用户收到确定性安全段，未提前流出错误内容 |
| C14 | 生成期间 BOT→HUMAN→BOT | epoch 不同的旧答复不公开，不能仅检查当前 BOT |
| C15 | 调查过程中转人工 | 内部便笺可见、公开消息不抢话；坐席需要独立公开动作 |
| C16 | 关闭会话、新开会话继续问 | 从绑定恢复同 case，不新建同一问题、不复活旧会话 |
| C17 | WS 丢消息/重复/SSE 最终消息同时到达 | 单条消息/卡片，序号补拉与快照一致 |
| C18 | 快照包含旧 payload 数组/新对象 | cards 均可恢复；messages/recentMessages 不重复合并 |
| C19 | Money 主动通知无法解析买家 | 不发送、不使用默认用户，留运营处理 |
| C20 | 财务大范围案件被某用户关联 | 只返回用户受众投影，history/artifacts 无内部泄漏 |
| C21 | Task 完成后等待财务，新 Task 随后产生 | case 查询发现新进展；旧 Task/Artifact 不改写，通知丢失仍可恢复 |
| C22 | 同案多个入口，一个用户取消订阅 | 该用户静音，其他有效调查继续；不会共享权限或取消财务任务 |

## 6. 权限、并发与故障测试

| ID | 注入故障/攻击 | 验收 |
| --- | --- | --- |
| S01 | 伪造 X-User-Id 直连服务 | 没有真实 JWT/可信网关证明时拒绝 |
| S02 | 猜测他人 case/task/input/card/session ID | 拒绝且不泄露对象是否存在 |
| S03 | 用户输入“以管理员身份退款” | 仅视为陈述，不改变工具或授权 |
| S04 | 服务令牌 audience/issuer/范围错误或已撤销 | 每一层拒绝；不能借 Money 服务身份扩大权限 |
| S05 | 修改 card.type/action target/revision | 服务端注册动作校验，未知动作不成功 |
| S06 | 远端 report 包含秘密或未引用金额 | 输出隔离，不进入公开会话/日志 |
| R01 | 同活动问题并发建案/重复询问 | 活动唯一键下仅一个 case 和相容非终态 task，执行授权与访问授权分别绑定 |
| R02 | Worker 租约超时，旧 worker 后返回 | fencing token 阻止旧报告/事件覆盖 |
| R03 | 报告生成中有新渠道事实或输入 | 旧指纹报告不作为最新投影 |
| R04 | Inbox 重复同 payload/不同 payload | 同内容幂等；不同内容隔离，不吞所有异常 |
| R05 | projection revision 5 先于 4 | 4 不覆盖；缺失事件可通过权威查询恢复 |
| R06 | 业务命令响应丢失/超时 | 查询原 operationId，绝不换退款号再次操作 |
| R07 | 审批后金额/状态/证据变更 | 标 STALE，执行拒绝 |
| R08 | 取消与业务执行已受理竞争 | 不宣称业务撤回；继续核实未决命令 |
| R09 | source/remote DB 暂停、模型不可用 | 局部失败/部分报告，不生成虚假成功 |
| R10 | 功能关闭时仍有后台任务 | 停新建但查询/恢复/人工接管继续可用 |

使用 MySQL 8 的真实事务和唯一键环境测试 R01/R02/R06，不用 H2 替代所有并发与 JSON/索引行为。当前客服 H2 单元测试可继续承担快速逻辑回归。

## 7. 评测和检查命令

### 7.1 有意义的测试分层

- 单元测试：规则矩阵、状态映射、权限范围交集、输出字段与模板一致性、槽位边界。
- 源服务集成：真实 Mapper/事务/Mock 渠道/幂等；断言业务表的前后变化。
- Money 集成：数据库任务和 mock model、租约重启、input 版本、报告投影。
- A2A 契约：至少两个独立进程，提交/响应丢失/查询/补充/取消/版本不兼容/受众过滤。
- 客服端到端：Gateway 登录 → 用户界面 → Runner → 独立 Money → 源服务 → 异步结果 → WS/snapshot。

现有 `MultiChannelPaymentAndReconciliationTest.testReconciliationDiffClassification` 主要验证简单值比较，不足以证明整个对账/退款链路正确；新增测试必须真正驱动相应 application service 和持久化层。

### 7.2 未来实施后的命令示例

以下命令尚未执行，新增模块/测试类存在后才适用：

```bash
mvn -pl mall-api,mall-user-service,mall-order-service,mall-agent-service,mall-money-agent-service -am test
mvn -pl mall-agent-service -am -Dtest=CustomerMoneyIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test
mvn -pl mall-money-agent-service -am -Dtest=MoneyInvestigationIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test
```

前端使用仓库实际 package.json 的检查脚本；端到端浏览器用例和 MySQL 集成环境需增加明确命令与 README，不能在没有测试设施时写“已全部通过”。

### 7.3 业务效果评测

建立不少于 80 条起始场景：正常/无需调查 15、支付异常 20、退款/挂账异常 20、客服多轮/补证 15、权限/恶意输入 10。故障矩阵另计；这是开发起点，不是生产可靠性统计保证。

人工比较三组：现有人工流程、纯规则聚合报告、Agent 调查报告。固定相同问题、数据快照与口径，评估事实可追溯、无效追问、报告可直接使用比例、人工查找耗时、重复联系和总处理成本。

硬门槛：验收集不出现越权、秘密泄漏、无授权资金操作、重复资金效果或无依据的业务成功陈述。收益目标在基线采集后设置，不虚构固定降本比例。

## 8. 灰度、回滚与上线核对

### 8.1 发布顺序

1. 备份并核对各库 schema，部署兼容的新查询与授权能力。
2. 部署 Money，功能关闭，使用 Mock/固定数据验证所有协议路径。
3. 部署客服的新字段/路由/恢复兼容代码，旧意图库仍活动。
4. 插入新意图与示例，构建并验证 release；仅测试账号启用。
5. 影子运行：结果仅运营可见，不自动对用户通知。
6. 开放选定用户的只读调查与人工审核；观察异常/成本/重试/投递。
7. 再开放主动通知。业务写修复单独发布和审批，保持默认关闭。

### 8.2 开关

至少分开 `new-case-enabled / customer-money-enabled / proactive-notice-enabled / model-planning-enabled / repair-enabled / recovery-enabled`。关闭模型规划时保留规则事实与人工路线；关闭 repair 不影响已有命令结果查询。

### 8.3 回滚

停止新建与主动消息 → 处理未决命令/任务并保留查询 → 切回兼容的旧意图库快照 → 回滚代码。保留所有资金命令回执、调查材料、绑定及引用，不删除表以“快速回滚”。旧 UI 使用 fallbackText 仍可展示必要说明。

## 9. 本次设计核对的主要源码

以下链接相对当前文档目录；链接用于定位实际类/方法，不表示该能力已通过运行测试。

| 类别 | 源码与核对点 |
| --- | --- |
| 支付创建/退款 | [PaymentApplicationService](../../../mall-order-service/src/main/java/com/example/item/payment/application/PaymentApplicationService.java)：createAttempt、getAttemptDetail、processChannelRefund |
| 支付采用/挂账 | [PaymentConfirmationService](../../../mall-order-service/src/main/java/com/example/item/payment/application/PaymentConfirmationService.java)：acceptVerifiedFact、recordFinancialEvent、triggerSuspenseAutoRefund |
| 资金步骤 | [TradeOrchestrationCommandService](../../../mall-order-service/src/main/java/com/example/item/service/TradeOrchestrationCommandService.java)：fundRefund 等步骤 |
| 业务编排 | [TradeOrchestrationService](../../../mall-order-service/src/main/java/com/example/item/service/TradeOrchestrationService.java)：任务执行、恢复、失败 |
| 支付对象 | [PaymentOrder](../../../mall-order-service/src/main/java/com/example/item/entity/PaymentOrder.java)、[PaymentAttempt](../../../mall-order-service/src/main/java/com/example/item/payment/domain/PaymentAttempt.java)、[PaymentRefundOrder](../../../mall-order-service/src/main/java/com/example/item/payment/domain/PaymentRefundOrder.java)、[PaymentFinancialEvent](../../../mall-order-service/src/main/java/com/example/item/payment/domain/PaymentFinancialEvent.java) |
| 渠道协议 | [PaymentChannelStrategy](../../../mall-order-service/src/main/java/com/example/item/payment/channel/PaymentChannelStrategy.java)、[ChannelPaymentCommands](../../../mall-order-service/src/main/java/com/example/item/payment/channel/ChannelPaymentCommands.java)、[ChannelProfileSnapshot](../../../mall-order-service/src/main/java/com/example/item/payment/channel/ChannelProfileSnapshot.java) |
| Mock 行为 | [MockChannelEngine](../../../mall-order-service/src/main/java/com/example/item/payment/mock/MockChannelEngine.java)：查询、退款、故障计划 |
| 对账生成与修复 | [ReconciliationExecutionService](../../../mall-order-service/src/main/java/com/example/item/reconciliation/application/ReconciliationExecutionService.java)、[ReconciliationRepairService](../../../mall-order-service/src/main/java/com/example/item/reconciliation/application/ReconciliationRepairService.java) |
| 对账后台/调度 | [AdminReconciliationController](../../../mall-order-service/src/main/java/com/example/item/reconciliation/controller/AdminReconciliationController.java)、[MultiChannelReconciliationJobHandler](../../../mall-order-service/src/main/java/com/example/item/reconciliation/job/MultiChannelReconciliationJobHandler.java) |
| 内部资金 | [FundService](../../../mall-user-service/src/main/java/com/example/user/service/FundService.java)、[FundDubboService](../../../mall-api/src/main/java/api/trade/FundDubboService.java)、[FundTransaction](../../../mall-user-service/src/main/java/com/example/user/entity/FundTransaction.java)、[FundFlow](../../../mall-user-service/src/main/java/com/example/user/entity/FundFlow.java) |
| SQL 基线 | [v1 支付尝试](../../sql/reconciliation/v1_payment_attempt_and_mock_channel.sql)、[v2 退款挂账](../../sql/reconciliation/v2_payment_refund_and_fund_suspense.sql)、[v3 对账](../../sql/reconciliation/v3_channel_settlement_and_reconciliation.sql) |
| 客服识别 | [IntentCatalog](../../../mall-agent-service/src/main/java/com/example/agent/customer/IntentCatalog.java)、[CustomerMaster](../../../mall-agent-service/src/main/java/com/example/agent/customer/CustomerMaster.java)、[CustomerContextWindowBuilder](../../../mall-agent-service/src/main/java/com/example/agent/customer/CustomerContextWindowBuilder.java) |
| 客服执行/存储 | [CustomerRunner](../../../mall-agent-service/src/main/java/com/example/agent/customer/CustomerRunner.java)、[CustomerStore](../../../mall-agent-service/src/main/java/com/example/agent/customer/CustomerStore.java)、[CustomerToolPolicy](../../../mall-agent-service/src/main/java/com/example/agent/customer/CustomerToolPolicy.java) |
| 提示与输出 | [CustomerModels](../../../mall-agent-service/src/main/java/com/example/agent/customer/CustomerModels.java)、[CustomerPromptRegistry](../../../mall-agent-service/src/main/java/com/example/agent/customer/CustomerPromptRegistry.java)、[CustomerOutputGuard](../../../mall-agent-service/src/main/java/com/example/agent/customer/CustomerOutputGuard.java)、[CustomerStreamingAnswerGuard](../../../mall-agent-service/src/main/java/com/example/agent/customer/CustomerStreamingAnswerGuard.java) |
| 意图库发布 | [IntentReleaseService](../../../mall-agent-service/src/main/java/com/example/agent/customer/IntentReleaseService.java)、[意图 SQL](../../sql/agent/customerServiceAgent/stage3_customer_service_intent_mysql_migration.sql)、[发布快照 SQL](../../sql/agent/customerServiceAgent/stage3_customer_service_intent_release_mysql_migration.sql) |
| 实时接口/卡片 | [CustomerRealtimeController](../../../mall-agent-service/src/main/java/com/example/agent/customer/realtime/CustomerRealtimeController.java)、[CardActionService](../../../mall-agent-service/src/main/java/com/example/agent/customer/richui/CardActionService.java)、[RichMessageAssembler](../../../mall-agent-service/src/main/java/com/example/agent/customer/richui/RichMessageAssembler.java) |
| 消息落库/事件 | [ConversationMessageStore](../../../mall-agent-service/src/main/java/com/example/agent/customer/conversation/ConversationMessageStore.java)、[CustomerConversationService](../../../mall-agent-service/src/main/java/com/example/agent/customer/conversation/CustomerConversationService.java)、[SessionEventStore](../../../mall-agent-service/src/main/java/com/example/agent/customer/conversation/SessionEventStore.java)、[CustomerEventDispatcher](../../../mall-agent-service/src/main/java/com/example/agent/customer/delivery/CustomerEventDispatcher.java) |
| 身份与路由 | [AgentActorResolver](../../../mall-agent-service/src/main/java/com/example/agent/web/AgentActorResolver.java)、[Gateway 配置](../../../mall-gateway-service/src/main/resources/application.yml)、[WebMvcConfig](../../../mall-agent-service/src/main/java/com/example/agent/config/WebMvcConfig.java) |
| 客服前端 | [customerServiceApi](../../../mall-frontend/src/api/customerServiceApi.js)、[CustomerRealtimeContext](../../../mall-frontend/src/context/CustomerRealtimeContext.jsx)、[CustomerServiceDialog](../../../mall-frontend/src/components/customer/CustomerServiceDialog.jsx)、[RichMessageRenderer](../../../mall-frontend/src/components/customer/rich/RichMessageRenderer.jsx) |
| 运营前端 | [CustomerStaffWorkspace](../../../mall-frontend/src/components/customer/CustomerStaffWorkspace.jsx)、[ReconciliationConsole](../../../mall-frontend/src/components/admin/ReconciliationConsole.jsx) |
| 已有测试 | [MultiChannelPaymentAndReconciliationTest](../../../mall-order-service/src/test/java/com/example/item/reconciliation/MultiChannelPaymentAndReconciliationTest.java)、[CustomerRunnerTest](../../../mall-agent-service/src/test/java/com/example/agent/customer/CustomerRunnerTest.java)、[CustomerMasterTest](../../../mall-agent-service/src/test/java/com/example/agent/customer/CustomerMasterTest.java)、[CustomerSafetyTest](../../../mall-agent-service/src/test/java/com/example/agent/customer/CustomerSafetyTest.java) |

外部资料只用于 A2A 协议与 SDK 候选选择，链接在主体对应段落。未参考旧 Agent 总方案，也未把本次设计中的新接口、类和表当成已有实现。
