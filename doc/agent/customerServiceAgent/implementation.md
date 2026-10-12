# 智能客服实现与运行说明

本次在仓库已有实现基础上补齐客服 P0 链路。设计依据为同目录 `implementation_design.md`；该设计稿中的“本次仅设计 / 不修改应用”描述保留为历史背景。本文件说明实际代码、运行准备与验证边界。

## 已落地的链路

- 用户会话创建、归属校验、关闭、24 小时过期、消息幂等、回答反馈。
- Spring AI Alibaba Graph 条件路由：输入检查、上下文、Master、槽位、订单、售后、规则、投诉、澄清、响应与输出检查。
- L1 整句规则；L2 完整意图发布快照检索；L3 结构化模型分类与解析重试；明确人工 / 风险信号不依赖模型。
- 最多两个业务实例；编号按实例隔离；澄清只恢复当前实例，其他待澄清实例继续保留，已完成实例不重复执行。
- 订单、时间线、售后、交付摘要的只读 Dubbo 接口，买家和卖家均需校验资源归属；管理员身份也不能绕过用户侧归属检查。
- `searchKnowledge` 工具使用节点白名单、服务端 ToolContext、公开启用文档、最新启用版本、父子分片回溯和模型 / 维度过滤；知识引用与工具调用共用调用编号。向量写入同步文档 visibility。
- 明确限制模型工具调用为一次受控检索；业务查询由确定性节点执行。响应通过代码整合经过校验的事实和引用，不增加模型改写调用。
- 意图库完整快照构建、独立评估、READY 原子切换、切回旧 READY 版本、撤销；缺向量或清单不一致时整次 L2 降级。
- MySQL 用户租约控制跨实例并发，默认每分钟 5 次、同用户 1 个运行；遗留超时运行可在下一次请求恢复为可回放的超时结果。
- 本地 Mock 分类与明确标记的演示知识，不访问外部模型或 Embedding；业务查询仍调用真实的只读订单服务。
- 管理端人工记录认领 / 完成、运行与工具追踪、意图库发布操作。
- 用户端悬浮按钮的鼠标 / Pointer Events 拖拽、4px 阈值、视口约束、位置存储、键盘操作、移动端面板、引用、候选订单、建议操作和失败重试。

P1 售前默认关闭。这里没有接入其他 Agent，也没有执行退款、放款、提现、售后修改等业务写操作。

## 主要入口

| 位置 | 职责 |
|---|---|
| `mall-agent-service/.../customer/CustomerController.java` | 用户、管理和发布 REST 接口 |
| `CustomerMaster.java` / `CustomerRunner.java` | 分层识别、实例恢复、Graph 节点与响应 |
| `CustomerModels.java` | DashScope ChatClient、受控 Tool Calling |
| `CustomerToolPolicy.java` / `CustomerOutputGuard.java` | 工具权限、引用 / 事实 / 敏感输出校验 |
| `IntentReleaseService.java` / `customer/intent-search.sql` | 发布版本和同一 PG 快照内的完整性校验 |
| `CustomerKnowledgeService.java` | 用户客服公开知识检索 |
| `CustomerStore.java` | 会话、租约、幂等、Trace、反馈、人工处理 |
| `CustomerBudget.java` / `CustomerHttpRequestFactory.java` | 剩余预算、取消、HTTP 总等待时间 |
| `mall-api/.../agent/AgentOrderQueryDubboService.java` | 只读业务边界与脱敏 DTO |
| `mall-order-service/.../service/AgentOrderQueryService.java` | 订单 / 售后归属检查 |
| `mall-frontend/src/components/customer/` | 客服悬浮入口、对话框、管理页面 |

管理端原有 RAG 索引诊断保留其管理语义；用户客服查询走专用公开知识检索服务及独立在线连接池。不要把管理端可检索到内部文档当作用户侧也可检索到。

## 数据库准备

应用不会自动执行迁移。本次没有连接数据库执行 DDL 或写入发布数据。

新环境按照以下顺序准备，各脚本在对应数据库执行：

1. `doc/sql/rag/stage3_rag_schema.sql`：MySQL RAG 基础表。
2. `doc/sql/rag/stage3_rag_pgvector_schema.sql`：PostgreSQL RAG 基础表。
3. `doc/sql/agent/customerServiceAgent/stage3_customer_service_agent_schema.sql`：MySQL 客服基础表及检索日志 Trace 列。
4. `stage3_customer_service_agent_pgvector_migration.sql`：PG knowledge visibility。
5. `stage3_customer_service_intent_mysql_migration.sql` 与 `stage3_customer_service_intent_pgvector_migration.sql`：原始意图编辑数据。
6. `stage3_customer_service_intent_release_mysql_migration.sql` 与 `stage3_customer_service_intent_release_pgvector_migration.sql`：完整发布快照、活动指针与发布向量。
7. `stage3_customer_service_runtime_mysql_migration.sql`：MySQL 用户运行租约。

上面第 4–7 项均位于 `doc/sql/agent/customerServiceAgent/`。已有环境仅执行尚未执行的增量；客服基础脚本对检索日志的 ALTER 不是可重复执行的，先核对已有列和索引。`CREATE TABLE IF NOT EXISTS` 不会修正旧表字段差异。

已有 PG 向量的 visibility 需要与 MySQL 文档可见性一致；重新索引后会写入当前文档的 visibility。用户客服始终再次校验 MySQL 权威元数据。

## 运行模式

依赖检查实际解析为 Spring AI Alibaba `1.1.0.0`、Spring AI `1.1.0`，工程使用 Java 21、Spring Boot `3.5.6`、Spring Cloud Alibaba `2025.0.0.0`。DashScope 适配器由代码显式构造，Mock 启动无需模型 Key。使用 Graph Core 完成确定性节点编排，未额外加载 Agent Framework、A2A、MCP 或 Studio。

默认 `CUSTOMER_SERVICE_PROVIDER=MOCK`。此模式仍需要已迁移的 MySQL、配置中的基础设施和订单服务（用于真实订单查询），不等同于无需数据库的内存应用。规则结果明确显示“本地演示资料（非正式平台政策）”。不要把 Mock 向量的相似度当作真实语义准确率。

真实模型模式配置：

```sh
export CUSTOMER_SERVICE_PROVIDER=DASHSCOPE
export CUSTOMER_SERVICE_MASTER_MODEL=qwen-turbo
export CUSTOMER_SERVICE_RESPONSE_MODEL=qwen-plus
# CUSTOMER_SERVICE_API_KEY 通过本地环境或部署密钥注入。
export RAG_EMBEDDING_PROVIDER=OPENAI_COMPATIBLE
# 设置 RAG_EMBEDDING_MODEL、RAG_EMBEDDING_BASE_URL、RAG_EMBEDDING_API_KEY。
```

数据库、Nacos 和 Dubbo 连接仍按项目环境配置；不要把 API Key 写入源码。用户通过既有网关登录进入商城，右侧客服入口自动挂载；管理员进入后台客服页。

在线限制为 L2 / 知识检索各 12 秒、模型 15 秒、业务工具 3 秒、run 90 秒；前端 / 网关等待 95 秒。HTTP 使用可取消的 JDK 异步传输，JDBC 使用独立短等待连接池、查询与网络超时。每个阶段取剩余预算，不让迟到结果成为成功响应。

## 意图库首次发布

通过数据库编辑源维护意图与问句，发布后不直接修改快照。管理页“意图库发布”提交独立评估集（3–200 条），需要覆盖全部启用业务意图及 OTHER 负例，不能复制训练问句。后台构建完成且完整性和评估全部通过后才能启用。

发布接口：

- `POST /api/admin/agent/intent-releases`，请求 `{ "evaluations": [{ "query": "...", "expectedIntent": "..." }] }`。
- `GET /api/admin/agent/intent-releases/{releaseNo}` 查看校验报告。
- `POST /api/admin/agent/intent-releases/{releaseNo}/activate` 启用或回滚到 READY 版本。
- `POST /api/admin/agent/intent-releases/{releaseNo}/revoke` 撤销，活动指针随之清空。

没有活动版本时，在线分类进入 L3；新版本失败不会切走旧版本。Mock 发布报告不能用于真实模式启用。

## 验证

```sh
mvn -o -pl mall-agent-service,mall-order-service -am test
mvn -o -pl mall-agent-service -am dependency:tree '-Dincludes=com.alibaba.cloud.ai:*,org.springframework.ai:*'
npm --prefix mall-frontend run build
mall-frontend/node_modules/.bin/oxlint --deny-warnings mall-frontend/src/components/customer mall-frontend/src/api/customerServiceApi.js
```

`-o` 适用于依赖已缓存的环境；首次构建移除该选项。当前验证覆盖客服专项、现有 RAG、订单服务，以及真实 Spring AI Tool Schema / ToolContext 执行机制（模型由测试替身提供，不调用云端）。浏览器实测覆盖拖拽不误触发、点击 / Enter / Space 打开、Esc 关闭、焦点恢复、刷新后位置保留、窗口缩放和 390px 移动端布局。

尚需在部署环境完成 MySQL / pgvector 迁移后的联调、真实 DashScope 与真实语义评估、并发压测。设计中的准确率和 P95 指标是验收目标，本次单元测试及浏览器检查不能证明这些指标已经达标。
