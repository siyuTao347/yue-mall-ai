# L2 意图库首次构建与启用

这是当前项目已有发布功能的操作说明。构建会调用真实 Embedding、写入 MySQL 发布快照和 PostgreSQL 向量；点击“启用此版本”后才切换在线 L2。本文和校验 JSON 的创建不会自动发布。

针对 2026-10-20 已完成向量生成、但分类校验未通过的情况，请先按 [本次后续操作步骤](L2_NEXT_STEPS.md) 导入补充训练示例并重建。

## 1. 确认 Embedding 配置

在启动 `mall-agent-service` 的 IDEA Run Configuration 中检查环境变量：

- `RAG_EMBEDDING_PROVIDER=OPENAI_COMPATIBLE`
- `RAG_EMBEDDING_API_KEY`：配置你的有效 Embedding 服务密钥。
- `RAG_EMBEDDING_BASE_URL`：与服务匹配；项目当前默认 `https://dashscope.aliyuncs.com/compatible-mode`，客户端会追加 `/v1/embeddings`，不要重复追加 `/v1`。
- `RAG_EMBEDDING_MODEL`：使用服务账号已开通、支持 1536 维输出的模型。项目当前配置默认值是 `qwen3.7-text-embedding`；配置文件中的名称不代表远端账号必然支持。
- `RAG_EMBEDDING_DIM=1536`：当前发布表和代码固定校验这个维度。
- `RAG_EMBEDDING_BATCH_SIZE=20`：客户端会把超过该值的逻辑批次拆成多个 HTTP 请求。Qwen3.7 文本向量模型单次最多 20 条，26 条发布示例应发送为 20 + 6；不要配置为原来的 32。

Chat 模型的 `CUSTOMER_SERVICE_API_KEY` 与上述 Embedding 配置是分开的。启动日志应出现 `[RAG] 使用 OpenAI 兼容 Embedding`，不能是 `使用 Mock 确定性 Embedding`。修改启动环境变量后重启服务。

确认 `customer-service.rag-enabled: true`。当前项目配置已经为 true。

## 2. 核对数据库初始化

在 MySQL 的 `db_agent` 中执行以下只读查询：

```sql
SELECT i.intent_code, i.status, COUNT(e.example_no) AS enabled_examples
FROM t_customer_service_intent i
LEFT JOIN t_customer_service_intent_example e
  ON e.intent_code = i.intent_code AND e.status = 'ENABLED'
GROUP BY i.intent_code, i.status
ORDER BY i.intent_code;

SELECT scene, active_release_no
FROM t_customer_service_intent_active_release
WHERE scene = 'CUSTOMER_SERVICE';
```

按仓库默认种子数据，应有 13 个启用的 L2 业务意图，每个 2 条示例；三个 `PRE_SALE_*` 意图默认禁用。`GREETING`、`BOT_IDENTITY`、`OTHER` 不建 L2 示例，记忆意图也不参与 L2。首次发布前 `active_release_no` 为 NULL 是正常的。

在服务实际连接的 PostgreSQL 数据库中执行：

```sql
SELECT to_regclass('rag_customer_service_intent_release_embedding');
```

返回表名表示发布向量表存在；NULL 表示缺表。初次发布前表内没有向量是正常的。

只有缺表时，才执行对应迁移。文件均在 `doc/sql/agent/customerServiceAgent/`：

1. MySQL 意图定义/示例表缺失：`stage3_customer_service_intent_mysql_migration.sql`。
2. MySQL 发布表/活动指针缺失：`stage3_customer_service_intent_release_mysql_migration.sql`。
3. PostgreSQL 发布向量表缺失：`stage3_customer_service_intent_release_pgvector_migration.sql`。

把 SQL 文件放在目录里不代表已执行。旧表 `rag_customer_service_intent_embedding` 中有数据，也不能代替新发布向量表。不要手工把发布状态或活动指针改成 READY/某个版本；应由发布接口完成验证和切换。

## 3. 在管理页面提交校验 JSON

1. 打开本地前端（Vite 默认 `http://localhost:3000`，以启动终端显示的地址为准）；未启动时在 `mall-frontend` 中执行 `npm run dev`。
2. 使用管理员账号登录；普通用户账号不会显示管理后台。
3. 点击管理后台的 **智能客服 → 意图库发布 → 构建完整意图库版本**。
4. 打开本目录的 `l2_release_evaluations.json`，复制全部内容到 **独立评估问句 JSON** 输入框，替换默认的两条占位数据。
5. 点击 **构建并校验** 一次，记下返回的 `releaseNo`。

该 JSON 是 14 条发布校验样例：13 个默认启用 L2 业务意图各一条，另含一个 OTHER 负例。它与仓库 SQL 训练问句、200 条正式评测问句没有归一化后的完全重复，但只是首次发布的基础校验，不是代表性准确率评测，也不保证当前向量模型能全部通过。

发布校验固定使用待发布快照，先执行 L2，低分或分差不足时调用与在线 Master 共用的 L3 分类、输出校验及置信度处理。COMPLAINT 与 HUMAN_HELP 在这里分别按语义标注；不运行 L1 或升级规则。校验不执行业务工具，缺少真实订单号会形成待澄清槽位，不妨碍意图标签比较。不要将该成绩称为完整三层或业务端到端成绩。

如果你已在数据库启用三个 `PRE_SALE_*`，且客服售前功能开启，请在数组中追加以下三项（注意 JSON 逗号）：

```json
{"query":"这款商品可以在哪些区服使用，购买后怎样交付？","expectedIntent":"PRE_SALE_PRODUCT"},
{"query":"我第一次来这里购买商品，从选择到付款要怎么操作？","expectedIntent":"PRE_SALE_PROCESS"},
{"query":"同一个买家购买这件商品有没有数量上限？","expectedIntent":"PRE_SALE_LIMIT"}
```

若只启用了其中一部分，只追加对应项。不要把未启用的意图作为必须命中的发布校验项；如果启用范围或源问句已被修改，以实际数据库内容为准重新核对覆盖和重复。

不要把 200 条 `intent_cases.json` 导入训练示例表；其中含 L1、多意图、显式上下文和记忆问题，也不能原样作为这里的单意图 L2→L3 发布校验。

## 4. 等待结果，再启用

发布任务在后台运行，页面没有自动轮询。每隔几秒点击 **刷新**：

- `BUILDING`：生成示例向量并写入 PostgreSQL。
- `VALIDATING`：检查版本完整性并运行你提交的校验问句。
- `READY`：所有发布校验通过，点击这一行的 **启用此版本**。
- `FAILED`：点击 **详情**，查看下方 JSON 的 `report`，先处理失败原因。

`READY` 表示版本可用，不表示已启用。当前页面启用成功后，行状态仍是 READY；可以用第 2 步的活动指针查询确认 `active_release_no` 等于刚才的 `releaseNo`。启用后通常不需要重启服务。

如果 `report.evaluations` 中存在 `passed: false`：

- 对 `evaluationMode: L2_THEN_L3` 的新报告，`l2Actual: OTHER` 表示 L2 回退；`actual` / `actualIntents` 是最终识别结果，回退不会自动算作成功。
- L2 直接接收了错误意图时，该题失败，不再用 L3 覆盖错误来掩盖误接收。L3 返回错误标签、多个意图或调用/解析异常时也失败。
- `status: ERROR` 的样本计入 `errorCount`，并保留在 `finalAccuracy` 的分母中。最终结果必须全部匹配才能 READY。
- 检查意图定义、训练示例和语义边界，补充独立训练样例后创建新版本。阈值校准使用另外的开发集；不要改目标答案、把校验问句抄进训练库或直接降低门槛来强行通过。

详情分别展示 L2 直接命中率、L3 回退数和最终准确率。默认 L2 条件仍为最高相似度严格大于 0.88，候选分差至少 0.05。旧报告没有 `evaluationMode`，其中 `source: L3_LLM` 仅表示回退标记，并未调用 L3；不要将旧报告按新口径解读。

如果 `report` 只有“构建或完整性/评估校验失败……”：

这是旧版本保存的报告，丢失的异常不能从报告恢复。更新代码并重启后创建新版本，新报告会记录 `stage`、`errorCode`、异常类型，以及可用的上游 HTTP 状态/脱敏错误说明或 SQLState。服务日志同步输出 `Intent release build failed`。例如 `EMBEDDING_EXAMPLES` 表示示例向量生成失败，`WRITING_VECTORS` 表示向量写入失败，`EVALUATING` 表示分类校验阶段。旧版本仍保留原报告。

构建提交后页面会加载完整版本详情，点击“刷新”也会更新当前详情，不再只显示 `releaseNo` 提交回执。

当前种子与 L3 的一处已知标签差异也需要业务确认：SQL 将“交易手续费”归为 SETTLEMENT_RULE，L3 提示词及 200 条评测约定归为 PLATFORM_RULE。本文基础校验避开了该歧义；正式评测前应统一训练源、L3 与金标的业务定义，不能为了提高分数随意改标。

## 5. 复测

先复测此前发生连接超时的案例：

```bash
python3 evaluation/intention-evaluation/evaluate_intents.py --case-id INT-029
```

再运行完整评测：

```bash
python3 evaluation/intention-evaluation/evaluate_intents.py
```

沿用原来的管理员 Token 环境变量。查看完整报告中的 `fallback_reasons`：启用成功后，应不再因为 `INTENT_RELEASE_UNPUBLISHED` 回退；若仍是 0 条 L2 命中，还需检查 `INTENT_RAG_UNAVAILABLE`、配置不匹配、低相似度/分差等原因。多意图或上下文问题直接去 L3 属于设计行为，不要求所有问题走 L2。

`INT-029` 在 2026-10-20 22:19:23 的异常是调用 DashScope Chat 接口时连接超时，随后被通用异常处理器包装成 HTTP 500。若单条复测持续同样报错，应检查运行 Java 服务的网络、代理、DNS 与到 DashScope 的连接；该日志本身不能确定具体网络原因，也不能通过改金标或槽位规则解决。发布 L2 使用独立 Embedding 接口，其可用性需要单独验证。
