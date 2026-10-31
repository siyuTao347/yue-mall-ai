# 独立 Money 微服务：开发任务、执行顺序与验收清单

日期：2026-10-31。配套方案：[独立微服务改造设计](independent_microservice_refactoring.md)。本文件是待执行任务，不是完成报告。

本文所有源码路径相对 `/Users/yu/IdeaProjects/mall-parent`。支付/退款渠道允许 Mock，模型也可以先用 Mock；事务、鉴权、重启恢复、数据关联和客服投递仍须执行对应验收。

收尾复核已出现 Money 服务与客服桥接原型。以下“新增”表示目标能力：文件已存在时原地完善，优先对照主方案第 2.3 节，不重复建模块，不覆盖有效修复。

## 1. 批次与完成门槛

| 批次 | 对应原阶段 | 交付目标 | 进入下一批的门槛 |
| --- | --- | --- | --- |
| R | 修复 M0/M1 | 可鉴权、可分页、可追溯、语义正确的事实源 | 审查 R01—R12 修复并有回归证据，隔离 MySQL 验证相关 SQL |
| S | M2 的运行时 | mall-money-agent-service 独立启动、持久调查、报告、恢复 | 停止客服后财务仍能调查，Money 重启可恢复 |
| A | M2 的协议 | A2A 委托、查询、补证、取消、授权视图 | 真实独立进程协议互通；重试和版本竞态验证 |
| C | M3 | 客服登记、通知、卡片、补证、人工接管闭环 | DROP_PAYMENT_NOTIFY 全链路和客服原功能回归 |
| O | M4 | 源事件、主动调查、运营工作台和影子评测 | 事件重放/丢失恢复、案件分配/复核、质量指标 |
| W | M5 | 审批后的受控修复、命令幂等和执行后核实 | 支付与退款一致性前置条件、业务命令故障验收 |

**本次独立服务改造的首个完整交付范围为 R → S → A → C。** O/W 是后续批次，不因启动独立服务就自动开启。S 骨架、DTO 和静态前端可以提前准备，但不能绕过 R 门槛将错误事实送给用户。

完成一个任务需要同时满足“实现、验证、记录”三项。一个 Controller 返回 200、模型生成文字、测试全用 Mock Mapper，都不等于独立微服务验收完成。

## 2. R 批次：修复当前事实源

### R-00：建立可核查基线

1. 阅读 [implementation_review.md](implementation_review.md)，逐项复核当前代码是否已被后续提交修复。
2. 记录 git 分支/提交和相关未提交改动；保留无关修改，不清理其他人的 target 或配置文件。
3. 检查根 POM 的既有模块及新增 Money 模块、实际 Java 包名、SQL 当前结构与调用方；对已有原型逐项核对缺口。
4. 记录实际可用的 Java/Maven/MySQL/Node 工具和隔离数据库入口。无法执行的验收标为未验证，继续不受影响的编码。

输出：更新 implementation_progress.md 的“当前基线、验证环境、R01—R12 状态”。不能保留“M0/M1 已完成”而没有本轮修复证据。

### R-01：鉴权与凭据（审查 R01、R02）

**修改位置：**

- `mall-api/src/main/java/api/agent/money/MoneyAccessContext.java`、`MoneySecuritySupport.java` 和对应安全契约。
- `mall-order-service/src/main/java/com/example/item/money/MoneySubjectResolver.java`、`MoneyOrderQueryDubboServiceImpl.java`。
- `mall-user-service/src/main/java/com/example/user/provider/MoneyFundQueryDubboServiceImpl.java`。
- 各提供方新增 MoneyRequestAuthorizer、MoneyScopeVerifier 与密钥配置绑定；纯验签组件可以共享，服务策略和私钥配置不放公共 JAR。

**实现顺序：**先定义 verified principal 与可信 issuer/audience/action → 为全部入口接入验证 → 再替换测试中的伪造 access。Order 范围证明必须由 Order 私钥签发，User 验证原 grant、scope 与接收服务。签名验证成功后仍核实当前订单归属和准确引用关系。

**验收：**空 access、默认密钥、错误 audience、过期 token、伪造 FINANCE、他人订单、超范围退款、被撤回 grant 均拒绝；拒绝时没有渠道调用和敏感数据返回。有效买家与岗位受限财务分别成功。不得通过将所有旧测试 access 改成 ADMIN 解决失败。

### R-02：对象解析、分页和指纹（审查 R06、R07）

**修改位置：** MoneySubjectSelector、MoneySnapshotQuery、CoverageInfo、MoneyOrderSnapshot；Order 的 Resolver、Query Provider、所需 Mapper；User 查询分页契约同步补齐。

**实现：**

1. 多编号输入全部校验一致性；订单号不能遮蔽冲突的 attempt/refund。
2. 实现复合游标，每个集合有 afterId、高水位和 completed。游标绑定授权 scope，限期有效。
3. attempts/refunds/events/diffs 等分别取 limit+1；返回每源 coverage，不能仅按前两个集合计算完整性。
4. 增加规范事实 fingerprint；金额、币种、业务状态、采用关系、编排、相关事件变化均反映在 fingerprint 中。
5. 增加 `issueFundScopeProof` 批量证明接口，单批最多 50 个已验证引用；追加发现不沿用已过时的范围证明。

**验收数据：**31 个 payment attempt、23 个 refund、45 个 event、21 个 diff，pageSize=10；逐页读取后无漏项/重复，第一页不能 COMPLETE。改变已存在退款的状态而不增行，指纹必须变化；只改变 traceId 或观察时间，证据指纹不变化。越权/过期/篡改游标拒绝，分页预算耗尽标 TRUNCATED。

### R-03：Mock 渠道的真实语义（审查 R03、R04、R12）

**修改位置：** `MoneyChannelObservationService`，现有 Order payment channel 策略/Mock 引擎的只读取证适配；新增 ChannelPaymentEvidence/ChannelRefundEvidence，不将退款命令 record 强行改成查询证据。

**实现：**

1. 必须还原原支付/退款 profile；缺失或不可解析时返回 PROFILE_UNRESOLVED，禁止 defaultFor。
2. 分离 existence、请求结果、业务状态；缺状态不补 SUCCESS，NOT_FOUND 不映射存在。
3. 从 Mock 存储记录读取币种、商户、业务号和金额；没有字段即 UNVERIFIED，不能复制入参充当验证结果。
4. 观察审计本地事务成功后才返回有效 evidenceRef；保存失败返回明确基础设施错误。

**验收：**成功、失败、处理中、NOT_FOUND、超时、缺身份、错商户、原 profile 丢失和审计失败九类独立 fixture；校验实际渠道调用次数、审计行和返回状态。Mock 标记贯穿快照、报告和公开投影。

### R-04：历史资金关联与幂等（审查 R05、R08、R09）

**修改位置：** User 的 MoneyFundQueryDubboServiceImpl、FundService、FundBusinessRefMapper/FundTransactionMapper；Order 的 `service/TradeOrchestrationCommandService.java`。

**实现：**

1. 对授权 refs 构造源服务认可的精确幂等键；查询 orderNo 为空、引用表为空的历史挂账/退款。
2. 移除 getSnapshot 内 backfill 调用；派生关联返回来源标识，不在查询中写业务引用。
3. 新增 FundBusinessReferences，业务入口传真实 attempt/refund/operation；Order 获取真实渠道退款受理编号后传给 User。
4. execute 的新事务与幂等命中分支都核对引用；旧事务缺引用可以受控补齐，不能再次动账；另一退款号重放必须冲突。
5. 需要历史回填时实现独立管理任务：可预览、有限批次、精确键、重复执行无重复引用；不随普通查询启动。

**验收：**历史挂账可查，整次查询无 INSERT/UPDATE；相同幂等键重复请求只动账一次；同 key 携带不同 refundNo 被拒绝。真实 refundNo 与 Order 受理记录相同，不是 `RF_ + orderNo` 的假编号。

### R-05：对账证据与时间（审查 R10、R11）

**修改位置：** MoneyReconEvidenceService、相关 Recon Mapper、Order/User 时间 DTO 转换和观察审计。

**实现：**按 task/run/file/item 原关联回溯 profile、商户、业务日、条目类型和解析完整性；窗口闭合不能仅看 run.status。channel-only REFUND 保留退款类别，REF_SUSPENSE 不依赖本地 orderNo 非空。旧 DATETIME 先按实际 Asia/Shanghai 解释，再输出 Instant；新审计时间按 UTC 保存。

**验收：**渠道单边退款、缺文件、错误商户、未闭合窗口、解析失败和跨日记录；例如历史本地 `2026-10-31 00:30:00` 输出 `2026-10-30T16:30:00Z`，不能直接加 Z。

### R-06：隔离数据库验证

使用全新隔离 MySQL 实例中的 db_order/db_user/db_agent/db_money_agent；因为当前脚本含固定 USE/CREATE DATABASE，不能仅连接到一个带 test 后缀的库就认为执行目标安全。

先准备最小的既有支付/退款/资金基础结构，再按 [SQL README](../../sql/agent/moneyerrorAgent/README.md) 执行当前阶段脚本。核对脚本存在性、JSON/时间映射、唯一约束、精确键查询计划和重放无损。不要复制真实业务数据作为 fixture。

通过门槛：R-01—R-05 的测试均实际执行；SQL/Mapper 验证不是 Mock。数据库环境不可用时可完成代码和单元测试，但阶段状态必须为“待数据库验收”。

## 3. S 批次：建立独立调查服务

### S-01：工程、配置与可执行包

**完善：** 已有 `mall-money-agent-service/pom.xml`、启动类及 config/resources。保留根 pom 模块注册，单数据源 db_money_agent；排除不需要的 Seata，复用锁定的 Java/Boot/Dubbo/Graph 基线。

**实现：**MoneyAgentProperties 绑定并校验预算、租约、开关；启动时验证必需 schema，禁止自动迁移；开启配置不能缺凭据。MOCK 模式不实例化依赖真实模型 Key 的客户端。Boot plugin 执行 repackage。

**验收：**单独 java -jar 启动、独立注册服务名、8086 可配置；依赖树没有三个业务服务 JAR，没有客服 pgvector/知识库启动依赖；与客服分开停止/重启。

### S-02：15 张表的映射与仓储

**新增：** persistence/entity、mapper、repository；使用 [money_error_v1_db_money_agent_core.sql](../../sql/agent/moneyerrorAgent/money_error_v1_db_money_agent_core.sql) 已有字段。

明确 MyBatis 的 JSON TypeHandler、Instant ↔ UTC DATETIME(3)、BigDecimal、乐观 version、空 active_dedupe_key。扫描条件和唯一键异常必须有专用仓储方法，不能将任意 DataAccessException 当作幂等命中。

**验收：**15 张表实体与 SQL 对齐；case/request 并发唯一键冲突正常恢复；写 report/projection/outbox 中任意一步失败均整笔回滚。查询数据库权限实际拒绝访问 db_order/db_user。

### S-03：受理、访问授权与任务生命周期

**新增：** MoneyCaseService、MoneyTaskService、MoneyInputService、MoneyGrantService、MoneyCaseQueryService。方法和锁顺序采用主方案第 8 节。

实现固定 requestHash/receipt、活动 case 去重、每案最多一个非终态 task、固定 execution scope、独立 task_access、输入版本和终态不可复活。范围不相容返回 CASE_TASK_BUSY；不得附着更大范围后悄悄提升执行权限。

**验收：**同请求并发 20 次仅一份受理结果；同 key 改 payload/主体冲突；相容调查复用任务；更高财务 scope 不扩权旧任务；终态新材料产生新 task，GetTask 仍返回旧报告。

### S-04：取证工具、规则与有限 Graph

**新增：** OrderMoneyQueryClient、UserFundQueryClient、MoneyFactCollector、MoneyToolExecutor/Policy、MoneyStatusNormalizer、MoneyRuleEvaluator、MoneyInvestigationGraph。

按主方案节点表实现 advance/checkpoint，不在工具里递归调用 Graph。每个 RPC 计算预算，sourceRpcCalls 写 checkpoint；每个业务工具有稳定 actionHash。每次读取都校验当前 grant，分页合并保留覆盖和观察时间。

首先实现 DROP_PAYMENT_NOTIFY 的 PAYMENT_CONFIRMATION_GAP，再实现正常处理中、身份冲突、来源不可用、历史不可关联、重复/迟到支付、挂账缺口及退款冲突。规则结果必须带 factId 和缺证据项。

**验收：**单个“聚合工具”不能发出无限 RPC；不完整覆盖不生成 VERIFIED_NORMAL；未知币种/金额/到账状态不能由模型补齐。

### S-05：模型边界与报告

**新增：** MoneyModelClient 的 MOCK/DashScope 适配、plan/report/projection schema、MoneyPlanValidator、MoneyReportAssembler/Guard、MoneyPublicProjectionBuilder、MoneyReportPublishService。

模型只选择白名单只读动作、待证实假设和解释。不可变报告按 task 保存；当前 case 投影按 audience 保存。金额使用显式十进制字符串、事实层级明确；用户陈述与权威事实分开。

**验收：**模型超时/非法 JSON/未知工具/伪造 factId/越权 target 均被控制；一次格式纠正后仍失败则降级。财务内部证据不进入买家 JSON；调查结束不等于问题已解决，渠道退款成功不等于实际到账。

### S-06：Worker、租约与恢复

**新增：** MoneyTaskWorker、MoneyTaskRecoveryJob、MoneyLeaseRepository。实现主方案的 claim → 提交租约 → 远程工作 → case/task 锁下保存模式。

持久化输入序号、sourceVersions、调用序号、预算和 nextNode；续租不递增业务 version。旧 fencing/owner 的结果丢弃；任务停止/授权撤回同时使旧执行失效。所有轮询有退避、上限和有界并发。

**验收：**两个 Worker 竞争同 task、进程被杀后租约恢复、模型返回时旧租约失效、补证与发布并发、发布后通知前崩溃。终态报告/公共事件不重复，允许只读查询在崩溃后重做但保留新观察身份。

### S-07：最小财务入口与独立运行证明

实现管理端建案、查案、列表和 reinvestigate；校验登录、岗位和对象范围。S 阶段可以通过 HTTP 测试客户端操作，不要求完整运营前端。

**验收步骤：**停止 mall-agent-service → 财务受限账号以 order/diff 发起 → Money 经 Dubbo 查询 Mock 渠道和真实隔离资金数据 → 产生报告 → 重启 Money 查询原 case/task → 观察执行与报告可恢复。记录进程、请求和数据库状态，不能仅在同一 Spring Test 中直接调 Service。

## 4. A 批次：真正的 A2A 服务与客户端

### A-01：依赖验证与传输适配

锁定经本项目验证的 A2A v1.0 SDK 版本，在独立测试中核对 Spring/Jackson 兼容和序列化；不要只沿用旧文档候选版本号。服务端只在 `transport/a2a` 依赖 SDK，客服只在 `money/transport` 依赖 SDK。

实现 Agent Card、MoneyA2aEndpoint/Adapter、TaskStateMapper 和 SkillCatalog；首版普通 JSON-RPC，未实现 streaming/push 不宣称支持。SDK 默认内存任务存储不能成为权威存储，所有请求落到 S 批次应用服务。按主方案第 11.4 节实现 returnImmediately、回执 metadata、条件取消 metadata 和非模型认证上下文，并验证 SDK 没有丢弃自定义字段。

### A-02：逐操作协议验收

| 调用 | 测试方法 | 必须断言 |
| --- | --- | --- |
| start | SendMessage + money.investigate.v1 | 持久化后返回 Task 与回执；taskId/contextId 可后续查询 |
| duplicate start | 服务受理后主动丢弃响应，再发送同 requestId | 同 case/task，错误 payload 返回冲突 |
| get | GetTask，分别使用买家、坐席、财务委托 | 只见各自视图；其他人的历史消息和材料不泄漏 |
| get case | money.case-status.v1 / GET_CASE | 即时 Message；可发现 currentTaskNo 变化，不产生调查任务 |
| provide input | SendMessage 关联 task/context，含 input/version | 版本正确时恢复；相同请求重放返回原回执；过期输入拒绝 |
| cancel | CancelTask + 已验证应用授权 | 合法可取消任务进入 CANCELED；共享需求/已终态按契约拒绝 |
| invalid request | 错协议版本、未知 method/schema、非法 ID/载荷 | 正确协议错误，内部异常和凭据不回传 |

所有协议错误映射由锁定版规范/SDK确定；业务 code 放在相应错误 data 或约定载荷中，不能一律 HTTP 200 + 空结果。客服“只静音订阅”是本地应用操作，不伪造为远端 Task 已取消。

### A-03：MoneyAgentClient 和故障注入

新增客服侧应用接口与 A2A 实现；注入配置的服务地址、超时、认证组件，不接受模型/浏览器提供 receiverURL。传输层不自行生成新的业务 requestId。

两进程覆盖连接拒绝、超时、截断 JSON、响应丢失、服务重启和旧客户端兼容。协议请求 ID、业务 requestId、traceId 各司其职，测试清楚断言哪个需要稳定、哪个可随尝试变化。

## 5. C 批次：客服闭环

### C-01：先修客服基础契约

修改 CustomerRealtimeController、ConversationMessageStore、CustomerStore、CardActionService；前端 customerServiceApi/customerRealtimeClient/CustomerRealtimeContext 同步。

核对现有 `/sessions` 等真实映射，修正误用 `/realtime/sessions` 的调用；保留 WS/ticket 的 realtime 路径。统一 messageNo、clientMessageId、eventSeq、payload/cards 与快照字段。用户访问 session/card 必须查服务端归属；未知动作明确失败。

Run.start 保存 expectedControlEpoch，finish 与后台投递在事务中比对同一 epoch。只比较当前 BOT 模式不足以防止 BOT→HUMAN→BOT 的旧回复。

**验收：**伪造 session/card、跨用户 event 补拉被拒绝；刷新重放不丢卡片；原订单/售后会话仍正常；旧生成任务不能在人工接管往返后公开。

### C-02：持久 dispatch 与真实受理绑定

按主方案新增 CustomerMoneyApplicationService/TriageService/Store/DispatchWorker，映射 db_agent 的 6 张 bridge 表。新 CustomerMoneyController 暴露主方案第 12.5 节 API；调用者 identity 来自登录上下文。

本地 register 只写 dispatch，在事务中保存稳定 requestId/hash 与 grantRef。Worker 领取后事务外调用 Money；成功后同事务写 receipt/binding 与可恢复投递记录。网络结果未知继续重放同请求，不能标失败后再次新建案件。

**验收：**Money 停机仍可在短请求内登记；登记文案不称“已受理”；恢复后只有一个 case/task/binding。客服收到真实回执但落库前重启，重试仍绑定原任务。

### C-03：上下文、意图和 Runner

1. IntentCatalog 注册三类资金意图及 MONEY_BRIDGE；元数据种子仍禁用。
2. 新 CustomerSlotRegistry，替换 CustomerMaster 所有白名单/候选/resolve/resume 路径；新增模板并切换实际调用点和 Mock 分支。
3. LOAD_CONTEXT 只加载本人授权案件与可信编号；先识别完整 PA_/RF_/REF_SUSPENSE_，避免内嵌订单被误认为用户指定对象。
4. executeTask 增加独立路由，respond 保留投影/卡片；资金事实由 AnswerAssembler 生成并经 OutputGuard 验证。
5. 一个 Run 的多个资金意图共享现有工具计数与超时预算；本轮不等待调查完成。

**验收语料：**普通支付查询、售后规则、售后进度、扣款未确认、退款冲突、卖家结算、人工升级、同轮两订单、只有退款号、多案件“那个调查”、切换订单。训练种子不能兼作全部验收集。

### C-04：同步、通知、输入和接管

实现 TaskSyncWorker、NoticeService、CardActionHandler 和通用 CustomerSystemMessageService。未终态 task 高频有界同步；仍活动 case 即使 task 已结束也低频查询。推送通知只加速，不取消轮询恢复能力。

inputRequest 持久化后生成带版本卡片；点击提交只登记 PROVIDE_INPUT dispatch，显示 SUBMITTING，真实远端受理后显示 SUBMITTED。重复点击与过期版本不会双提交。

投递按 binding → session → delivery/card 锁顺序：BOT 公开，HUMAN/QUEUED 转便笺，关闭会话等待合法恢复。通知无法确定收件人时留待运营，不能默认某个用户。

**验收：**乱序/重复通知不回退 projectionRevision；用户断线重连只见一次消息；人工接管时没有机器人公开抢话；授权撤回后即使通知晚到也不再披露。

### C-05：前端、意图库发布与端到端

新增 MoneyInvestigationCard/MoneyInputCard、更新 RichMessageRenderer，坐席工作台展示允许的摘要与便笺。金额/状态/动作由服务端投影决定；浏览器不收到内部报告再自行隐藏。

先通过 C-01—C-04，再启用三类意图定义/样例，按现有 IntentReleaseService 创建、校验、激活完整快照。不能只执行种子 SQL 就认定在线路由更新。

最终跑通主方案两条独立进程链路；记录用户文本、本地 dispatch、远端受理、快照、报告、卡片、补证和人工接管结果。停用新意图后，既有活动调查仍可查询和恢复送达。

## 6. O/W 后续批次的明确边界

| 工作项 | 改造位置 | 验收重点 |
| --- | --- | --- |
| O-01 源事件 | Order/User 的业务事务 + 各自 t_money_domain_outbox | 业务变更与事件同时提交；失败重试，不跨库代写 |
| O-02 主动调查 | MoneySourceInboxWorker、规则与受限系统 grant | source+eventId 去重；同范围合并，乱序事件不降低版本 |
| O-03 运营工作台 | Money 管理 API、新 MoneyInvestigationConsole | 岗位可见性、分配 CAS、复核、关闭理由、历史报告 |
| O-04 影子评测 | 固定 Mock/隔离业务 fixture、人工标注评测集 | 分类、证据准确性、误建案、工具/时延预算；不以模型文案流畅作唯一指标 |
| W-01 前置一致性 | 现有支付确认、退款受理和资金编排 | 原方案 M5 门槛全部通过后再开放写动作 |
| W-02 受控命令 | proposal 审批、Order 命令表/接口、CommandTracker | 审批绑定参数 hash/证据版本，operationId 端到端幂等 |
| W-03 执行后复查 | Money 新任务 + 权威业务回执 | 超时只查原操作，不换 operationId 重发；执行成功后重新核实事实 |

W 仍不默认开放 Agent 自主退款。Mock 支付不豁免审批、幂等与余额一致性；这些是系统逻辑，与是否接入真实银行无关。

## 7. 最小故障验收矩阵

以下测试名为建议新增名称，当前不存在不表示已有测试通过。可以按项目测试惯例组织，但不得删去对应断言。

| ID | 建议测试组 | 必须构造的失败条件 | 通过结果 |
| --- | --- | --- | --- |
| T01 | MoneyProviderAuthorizationTest | 空身份/伪造角色/错误 audience | 所有入口拒绝且不披露事实 |
| T02 | MoneyGrantRevocationTest | 取证中撤回、旧结果晚到 | 旧结果不发布，任务停止/等待授权 |
| T03 | MoneyProfileEvidenceTest | 原 profile 丢失 | 渠道调用 0 次，未知而非默认渠道 |
| T04 | MoneyChannelSemanticsTest | 缺状态、NOT_FOUND、错币种 | 无假成功/假匹配 |
| T05 | MoneyObservationAuditTest | 审计 INSERT 失败 | 无伪造 evidenceRef |
| T06 | MoneyFundLegacyQueryTest | orderNo=null 且无引用 | 精确历史关联可查，无写操作 |
| T07 | FundBusinessReferenceTest | 资金幂等重放带另一 refund | 拒绝冲突且无二次动账 |
| T08 | MoneySnapshotPaginationTest | 每集合分别超过一页 | 游标完整续读，截断显式 |
| T09 | MoneyFingerprintTest | 同数量的状态/金额改变 | 指纹改变，时间噪声不改变 |
| T10 | MoneyReconEvidenceTest | 单边退款/缺文件/窗口未闭 | 分类和覆盖准确 |
| T11 | MoneyTimeMappingTest | 本地午夜跨 UTC 日期 | 正确 Instant 和业务日 |
| T12 | MoneyRequestIdempotencyTest | 并发重复、成功响应丢失 | 同一受理回执 |
| T13 | MoneyTaskScopeTest | 财务加入买家窄范围 task | 不提升原执行权限 |
| T14 | MoneyReportAtomicityTest | 写投影/Outbox 时失败 | 报告相关事务全回滚 |
| T15 | MoneyWorkerRecoveryTest | 杀进程、双 Worker、旧租约 | 可恢复且不覆盖新结果 |
| T16 | MoneyInputRaceTest | 补证与旧 Worker 发布并发 | 输入版本生效，旧发布拒绝 |
| T17 | MoneyModelBoundaryTest | 模型越权/虚构证据/超预算 | 拒绝并确定性降级 |
| T18 | MoneyA2aWireContractTest | 独立进程发送实际 JSON-RPC | 正确协议模型与持久结果 |
| T19 | MoneyHistoricalTaskViewTest | 同 case 创建第二次调查 | 旧 task 保留旧报告 |
| T20 | CustomerMoneyDispatchTest | 远端受理后客服崩溃 | 重放绑定同 task |
| T21 | CustomerMoneyNotificationTest | 通知重复、乱序、会话关闭 | 无重复消息/版本回退 |
| T22 | CustomerMoneyHandoffTest | BOT→HUMAN→BOT | 旧答复不公开，合法便笺可查 |
| T23 | CustomerMoneyCardAccessTest | 他人 card/session、过期输入 | 拒绝，无越权补证 |
| T24 | CustomerMoneyOutputTest | 渠道成功但无到账证据 | 不声称买家已到账 |
| T25 | MoneyStandaloneScenarioTest | 客服进程停止 | 财务发起和报告继续工作 |
| T26 | MoneyMigrationMySqlTest | 首次迁移/重放/结构漂移 | 无损重放，漂移被发现 |

R 阶段至少 T01、T03—T11、T26；S 增加 T02、T12—T17、T19、T25；A 增加 T18；C 补齐 T20—T24。每批仍执行受影响的既有业务回归。

## 8. 实际执行命令与结果记录

以下命令从项目根目录执行，属于实施时建议，不代表本次文档编写已经执行。新模块和新测试必须先存在。前端当前只有 dev/build/lint/preview 脚本，不能虚构已有 npm test。

现有专项回归命令：

```bash
mvn -o -pl mall-api,mall-order-service,mall-user-service -am -Dtest=MoneyContractAndSecurityTest,MoneyOrderQueryIntegrationTest,MoneyFundQueryDubboServiceImplTest -Dsurefire.failIfNoSpecifiedTests=false test
```

该命令现有 12 项测试以 Mock 依赖为主，只作回归起点。新增回归需要明确指定或纳入正常 test；不能通过 `failIfNoSpecifiedTests=false` 在目标模块没有测试时宣称验收成功。

新模块完成后的测试与打包：

```bash
mvn -pl mall-money-agent-service -am test
mvn -pl mall-money-agent-service -am -DskipTests package
```

数据库/独立进程集成测试若使用 Failsafe，实施时必须先配置插件及隔离测试 profile，并记录真实命令；若沿用 Surefire，则使用其实际测试命名与包含规则。不能把未接线的 `mvn verify` 当作数据库验收。首轮依赖未缓存时不要强制 -o；离线失败记录为依赖未就绪。

使用隔离环境配置启动已打包服务：

```bash
java -jar mall-money-agent-service/target/mall-money-agent-service-0.0.1-SNAPSHOT.jar --spring.profiles.active=local
```

启动前通过环境配置注入隔离数据库、注册发现、测试密钥以及启用开关；此命令不会自动迁移数据库。不要依赖应用现有默认业务连接做实验。

客服接入后进行相关 Java 回归，再进入前端目录执行其已有检查：

```bash
mvn -pl mall-agent-service,mall-money-agent-service -am test
cd mall-frontend
npm run lint
npm run build
```

每阶段在 implementation_progress.md 留下以下记录：

```text
批次/任务：R-01 ...
状态：已实现 / 单元已验证 / 隔离数据库已验证 / 独立进程已验证 / 待验证
源码与 SQL：具体文件，以及迁移是否实际执行
环境：Java/Maven/MySQL/Node 版本与隔离环境标识，不含密码
命令与结果：实际命令、测试数量、报告文件、失败原因
场景证据：requestId / caseNo / taskNo / operationId（适用时）
剩余项：明确未完成的行为及其影响
下一批入口：前置门槛与首个任务
```

本次仅增加设计文档，不替任何阶段填写“已完成”，也不自动执行建表、发布意图库或启动资金写能力。
