### 一、 智能体编排与生成质量（Agent & LLM）

1. **知识库回答缺乏大模型归纳合成（RAG 仅做拼接）**
* **现状**：`CustomerRunner.policy()` 中调用 `models.retrieve()` 执行完 `searchKnowledge` 工具后，直接用 `StringBuilder` 将召回的多个 `chunk` 原文按 `[S1] ... [S2] ...` 硬编码拼接返回给用户，并未将工具结果回传给 LLM 生成针对性回答。


* **建议**：在 `searchKnowledge` 召回知识片段后，增加一轮 LLM 生成调用（或由 `RESPONSE_AGENT` 统一做 Synthesis），要求模型基于召回的父块（Parent Chunk）上下文，结合用户的 `rewrittenQuestion` 进行自然语言总结，并在句末标注 `[S1]` 引用，避免用户直接阅读大段生硬的规则原文。


2. **规则与售后工具调用未形成完整的 Tool Loop**
* **现状**：`AFTER_SALE_AGENT` 虽然在 `CustomerToolPolicy` 中配置了 `queryDeliveryEvidenceSummary` 和 `searchKnowledge` 权限，但在 `CustomerRunner.executeNext()` 中，仅当意图为 `AFTER_SALE_PROGRESS` 或 `AFTER_SALE_STATUS_COMBINED` 时硬编码调用了 `queryDispute` 和 `queryOrder`，既没有调用发货凭证摘要接口，也无法在单个意图内同时结合订单/纠纷事实与知识库规则。


* **建议**：允许 `AFTER_SALE_AGENT` 在处理售后咨询时按需组合调用“事实查询工具（如 `queryOrder`、`queryDeliveryEvidenceSummary`）”与“规则检索工具（`searchKnowledge`）”，使智能体能根据订单当前实际状态给出精准的售后规则指引。


3. **预售智能体（PreSaleAgent）能力尚未闭环**
* **现状**：`IntentCatalog` 定义了 `PRE_SALE_PRODUCT`、`PRE_SALE_PROCESS`、`PRE_SALE_LIMIT` 意图及 `itemId` 槽位，且设计文档规划了 `queryPublicItem` 工具，但 `CustomerRunner` 中尚未接入 `AgentItemQueryDubboService`，`CustomerMaster.resolve()` 中也没有对 `itemId` 的正则或上下文提取逻辑。


* **建议**：在 `CustomerRunner` 中注入商品查询 Dubbo 服务，完善 `PRE_SALE_AGENT` 节点逻辑与 `itemId` 槽位提取规则，打通“商品实时库存/价格查询 + 商品 RAG 知识库问答”的售前导购闭环。


4. **Graph 编排架构与状态解耦**
* **现状**：在 `CustomerRunner.send()` 中，每次请求都会重新实例化并编译 `new StateGraph().compile()`，且状态并未存储在 Graph 的 `OverAllState` 中，而是通过外部闭包对象 `Run` 强行传递，所有 Node 均返回空 `Map.of()`。


* **建议**：将 `StateGraph` 的拓扑构建与 `compile()` 提升为 Spring Bean 单例初始化，避免每次对话重复编译图结构；同时将 `Run` 中的上下文状态规范化映射到 Graph 的 State 机制中，提升图编排的可观测性与扩展性。


5. **多意图与长对话摘要（Summary）机制过于简陋**
* **现状**：`CustomerStore.summary()` 仅将当前轮次的 `intentCode + slots` 进行简单字符串拼接并截断前 800 字符，直接覆盖 `t_agent_session.summary`，并未结合历史对话生成真正的语义摘要，也未累积历史轮次的关键信息。


* **建议**：采用增量式槽位状态机或异步 LLM 摘要机制，在会话轮次超过阈值（如 10 轮）时，对早期历史消息进行压缩总结，确保多轮长对话不会丢失前置背景。



---

### 二、 检索增强生成与意图识别（RAG & Intent）

1. **知识检索缺少 Hybrid（混合检索）与 Rerank（重排序）落地**
* **现状**：设计文档规划了 `KEYWORD`、`VECTOR`、`HYBRID`（基于 RRF 融合）及降级模式 `DEGRADED`，且分块时已提取 `keywords_json`，但 `KnowledgeStatsService` 和 `CustomerKnowledgeService` 目前仅支持纯向量检索（`VECTOR`），遇到专有名词（如具体武器磨损度、特定错误码、订单号前缀）时纯向量检索容易漏召回，且当 pgvector 故障时无法自动降级到 MySQL 全文/关键词检索。


* **建议**：尽快在 `CustomerKnowledgeService` 中补齐基于 MySQL `FULLTEXT (ngram)` 或 `keywords_json` 的关键词检索分支及 RRF 融合逻辑；在 pgvector 不可用或超时时自动触发 `DEGRADED` 模式降级至关键词检索，保障客服核心问答可用性。


2. **L2 意图识别每次请求全量拉取快照并做内存 SHA-256 校验**
* **现状**：`IntentReleaseService.recognize()` 在每次用户提问时，都会从 MySQL 查询 `t_customer_service_intent_release`、`item`、`example` 三张表全量数据，并在业务线程中执行 `validate(s)` 对所有样本逐条重算 NFKC 归一化与 SHA-256 哈希，随后将全量样本序列化为 JSON 传给 PostgreSQL 的 `jsonb_to_recordset` 进行 JOIN。


* **建议**：由于发布的 Release 是不可变快照（Immutable Snapshot），应在内存中按 `release_no` 缓存已校验通过的 `Snapshot` 对象（如使用 `ConcurrentHashMap` 或 `Caffeine`），仅轻量查询 `t_customer_service_intent_active_release` 获取当前激活版本号；同时在 PG 向量表已通过 `release_no` 隔离的前提下，无需每次检索都将全量样本 JSON 跨网络传给 PG 做二次 Hash Join，可在发布激活阶段完成一致性校验，大幅降低在线 L2 检索延迟与 DB 负载。


3. **在线检索前全表查询有效文档 ID 列表存在性能隐患**
* **现状**：`CustomerKnowledgeService.retrieve()` 第一步先去 MySQL 查询所有符合 `doc_type` 且处于最新 `ENABLED` 版本的 `document_id` 列表，再通过 `WHERE document_id IN (:ids)` 传给 pgvector。随着知识库文档数量增长，`IN` 列表会变得极大，拖慢 SQL 解析与向量索引过滤效率。


* **建议**：在文档启用/禁用（`enable`/`disable`）时同步维护 pgvector 中对应记录的 `status`（或增加 `is_latest_enabled` 标记），或者对激活文档 ID 集合做本地短 TTL 缓存，避免每次检索都执行子查询 `MAX(v.version)`。


4. **双套 Embedding 客户端与数据库连接池重复建设**
* **现状**：系统中同时存在 `OnlineEmbedding`（客服包下，硬编码 1536 维与兼容路径）与 `EmbeddingClient`（`rag.embed` 包下）两套实现；同时 `CustomerDatabase` 通过强转 `(HikariDataSource)` 克隆了 `primaryDataSource` 和 `vectorDataSource`，导致单个实例内维持了 4 个数据库连接池。


* **建议**：统一收敛 `EmbeddingClient` 接口（支持返回 Token 用量与动态超时传递）；数据源层面可通过配置合理的隔离连接池 Bean 或利用 `CustomerBudget` 控制超时，避免在业务组件中反射/强转克隆底层 DataSource。



---

### 三、 并发控制、性能与可靠性（Performance & Reliability）

1. **同步阻塞模型与无流式输出（Streaming）影响用户体验**
* **现状**：`POST /api/agent/sessions/{session}/messages` 采用同步阻塞返回，虽然有 L1/L2 加速，但一旦落入 L3 大模型分类与多工具调用，响应耗时可达数秒至十余秒，前端只能长时间等待。


* **建议**：增加 `SSE (Server-Sent Events)` 流式接口支持，实时向前端推送状态节点（如“正在查询订单…”、“正在检索规则…”）及流式文本输出。


2. **数据库行锁与连接超时在 `CustomerDatabase` 中的冲突隐患**
* **现状**：`CustomerDatabase.bounded()` 将所有 Statement 的 `queryTimeout` 硬编码为 `1秒`，且将底层 JDBC Connection 的 `networkTimeout` 动态修改为 `remaining(1000)` 且从未恢复默认值（连接归还连接池后仍带着被篡改的 `networkTimeout`）。同时，`CustomerStore.start()` 在事务中对 `t_agent_user_gate` 和 `t_agent_session` 执行 `SELECT ... FOR UPDATE`，在高并发或慢查询抖动时极易触发连接级 `networkTimeout`，导致 Hikari 连接被直接销毁重建。


* **建议**：不要在每次执行 Statement 时直接修改共享连接池物理连接的 `networkTimeout`（或必须在借出/归还时重置）；将并发门控与限流（每分钟 5 次请求、单用户租约锁）迁移至 Redis（利用 Lua 脚本或分布式锁），减轻 MySQL 行锁争用与频次统计的 `COUNT(*) JOIN` 开销。


3. **会话消息查询存在 N+1 查询问题**
* **现状**：`CustomerStore.session()` 在获取最近 100 条历史消息时，在 `for` 循环中针对每一条 `role='AGENT'` 的消息单独执行一次 `SELECT result_json FROM t_agent_run WHERE run_no=?`，最多可触发 50+ 次数据库单点查询。


* **建议**：改为使用 `LEFT JOIN t_agent_run` 一次性查出，或收集所有 `run_no` 后使用 `WHERE run_no IN (...)` 批量查询并组装。


4. **意图发布构建（IntentReleaseService.build）串行 Embedding 耗时过长**
* **现状**：`IntentReleaseService.build()` 在构建意图版本和执行评测集（最多 200 条）时，在 `for` 循环中逐条调用远程 Embedding 接口，且每条样本单独开启一个数据库事务写入 PG。当样本数和评测集较大时，单次构建需要数百次网络往返，极易超时失败。


* **建议**：利用批量 Embedding 接口（如按 `batchSize=32` 批量请求向量）并批量写入 `rag_customer_service_intent_release_embedding`，将数百次 RPC 与 DB 写入压缩至十几次以内。



---

### 四、 安全、合规与可维护性（Security & Maintainability）

1. **鉴权逻辑分散且与拦截器不一致**
* **现状**：`AdminAuthInterceptor` 拦截了 `/api/admin/agent/**` 并将用户信息存入 `UserContext`（优先信赖网关透传的 `X-User-Id` 和 `X-User-Role` Header），而 `CustomerController` 内部又自己实现了静态方法 `actor(HttpServletRequest)` 和 `admin(HttpServletRequest)`，强制重新解析 `Authorization` JWT Header，当网关仅透传 `X-User-Id` 时会导致行为不一致。


* **建议**：统一认证上下文提取逻辑，通过全局 Filter/Interceptor 构建 `AgentActor` 并注入 Spring MVC 参数解析器（`HandlerMethodArgumentResolver`），消除 Controller 层的重复鉴权代码。


2. **配置文件存在硬编码敏感信息与开发痕迹**
* **现状**：
* `AgentApplication.java` 的 `static` 块中硬编码了 macOS 本地网卡名称 `en0` 与 `utun.*`（`dubbo.network.interface.preferred=en0`），在 Linux 容器化部署（通常为 `eth0`）时会导致 Dubbo 网卡绑定异常。


* `application.yml` 与 `application-customer-eval.yml` 中硬编码了内网 IP（`100.121.74.115`）、数据库明文密码（`root/root`）及专属 Workspace 域名。




* **建议**：移除 `AgentApplication` 中的静态网卡硬编码，改由各环境的外部配置文件或环境变量注入；所有数据库凭证、内网地址统一收敛至环境变量或配置中心（Nacos）。


3. **跨库数据一致性缺乏定时对账兜底（Reconcile Job）**
* **现状**：虽然设计文档 7.7 节规划了 MySQL 与 pgvector 的定时对账补偿任务（`KnowledgeReconcileJob`），但在实际代码中，当文档删除后异步清理向量失败（`cleanupVectors` 重试 3 次仍失败仅打印 `log.error`），或向量写入成功但 MySQL 状态更新异常时，缺乏后台定时巡检与自动修复机制。


* **建议**：落地定时对账任务（`Scheduled` 或 XXL-Job），定期比对 MySQL `t_knowledge_document` / `t_knowledge_chunk` 与 PostgreSQL `rag_chunk_embedding` 的状态与孤儿数据，实现最终一致性闭环。