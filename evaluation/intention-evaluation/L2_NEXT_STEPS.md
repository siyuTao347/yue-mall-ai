# 本次 L2 构建失败后的操作步骤

最新失败版本 `IR42efb93cc10a48e2aa318444f258e9e3` 已完成 182 条示例的快照和向量生成。旧的纯 L2 校验通过 10/14：9 条业务问题直接命中，1 条 OTHER 正确拒识，另 4 条业务问题回退。

现按确认后的要求，发布验收改为 **L2→L3 最终意图匹配**：L2 未命中时真实调用 L3，不把回退直接计为通过。全部最终结果正确、且没有异常，才会成为 READY。原有相似度 `0.88` 和分差 `0.05` 保持不变。

**已导入 156 条补充样例的情况下，跳过第 1 步。先在 IDEA 停止并重新运行 `mall-agent-service`，刷新前端，再从第 2 步创建新版本。** 旧 FAILED 版本保留原来的报告。

## 1. 执行补充 SQL

在 IDEA Database、Navicat 等工具中，连接 **mall-agent-service 实际使用的 MySQL `db_agent`**，在同一个连接中执行以下文件的全部 SQL：

`doc/sql/agent/customerServiceAgent/stage3_customer_service_intent_examples_expand_20261020.sql`

文件新增 156 条训练示例，覆盖 13 个默认启用的 L2 意图，每类新增 12 条。若仍是原来的每类 2 条种子，执行后每类 14 条，共 182 条。

执行结束应看到：

| 结果字段 | 首次执行预期值 |
| --- | --- |
| `source_ready` | `1` |
| `conflicting_existing_rows` | `0` |
| `inserted_examples` | `156` |
| `matching_batch_examples` | `156` |
| 每个意图的 `enabled_examples` | 默认种子基础上为 `14` |
| 每个意图的 `stale_version_examples` | `0` |

完全相同的 SQL 再次执行时，`inserted_examples = 0`、`matching_batch_examples = 156` 是正常结果。若 `source_ready = 0`，表示某个源意图缺失、禁用或版本无效；若 `conflicting_existing_rows > 0`，表示同编号数据与本批不同。出现这两种情况时本批不会插入，需要先核对源数据，不能把零新增误认为执行成功。

本步骤增加的是 MySQL 训练源，向量由下一步构建时生成。SQL 不修改原有示例、意图阈值、发布快照或活动版本。

## 2. 使用原来的 14 条问题重建

若提交时出现 `TransactionTimedOutException`，堆栈指向 `IntentReleaseService.create`，属于保存发布快照时超过旧的 3 秒事务时限，尚未进入 Embedding。代码现已改为分批写入，并给快照创建单独设置 30 秒事务时限。**先在 IDEA 中停止并重新运行 `mall-agent-service`（8085），加载修改后的代码，再执行本步。** 已成功导入的训练样例可以继续使用，无需重新导入。

1. 管理员登录前端，进入 **智能客服 → 意图库发布 → 构建完整意图库版本**。
2. 打开 `evaluation/intention-evaluation/l2_release_evaluations.json`，将完整 JSON 数组复制到 **独立评估问句 JSON** 输入框。
3. 保持当前相似度阈值 `0.88` 和候选分差门槛 `0.05`，点击 **构建并校验**，创建一个新版本。
4. 每隔几秒点击 **刷新**，等待新版本的状态和详情更新。

这次继续用原来的 14 条校验问题，便于和失败版本比较。不要将校验问题导入训练表，也不要修改旧失败版本的状态。只导入训练源数据通常不需要重启服务；本次不要求额外执行 PostgreSQL SQL。

## 3. READY 后启用；FAILED 时查看新报告

- 新版本为 **READY**：点击该行的 **启用此版本**。
- 新版本为 **FAILED**：打开详情，查看 `evaluations` 中的 `actualIntents`、`expected`、`status` 和 `errorCode`。L2 误接收、L3 分类错误、多意图与单意图目标不匹配、模型调用失败均不能通过。

新报告的 `evaluationMode` 应为 `L2_THEN_L3`，主要指标为：

| 字段 | 含义 |
| --- | --- |
| `l2Coverage` | L2 直接接收数 / 全部校验问题数 |
| `businessL2Coverage` | 业务问题中由 L2 直接接收的比例，不含 OTHER 负例 |
| `l2Precision` | L2 接收结果中正确的比例；没有接收时为 null |
| `l3FallbackCount` | 需要进入 L3 的问题数，包括 OTHER 负例 |
| `correctCount`、`finalAccuracy` | L2→L3 最终匹配数量及其占全部问题的比例 |
| `errorCount` | 调用或解析异常数；这些样本保留在准确率分母中 |

单条结果的 `l2Actual: OTHER` 表示检索层回退占位；`actual` / `actualIntents` 才是最终分类。校验只执行分类，不创建会话、查询订单、转人工或修改记忆；也不包含 L1、升级规则和上下文恢复，因此不能把它等同于完整三层 200 条评测成绩。

可以在 MySQL 中确认活动版本：

```sql
SELECT scene, active_release_no
FROM t_customer_service_intent_active_release
WHERE scene = 'CUSTOMER_SERVICE';
```

`active_release_no` 应等于刚刚启用的新版本编号。READY 只表示可启用，不代表已经切换在线版本。

## 4. 启用后再跑 200 条评测

在项目根目录执行，沿用之前已配置的管理员认证：

```bash
python3 evaluation/intention-evaluation/evaluate_intents.py
```

重点看是否出现 `L2_RAG` 分类、是否仍有 L2 不可用降级、ERROR 数量，以及整套样本的匹配率和覆盖率。发布校验直接测 L2；这 200 条通过完整识别流程，部分问题会被前置规则或上下文接管，两者的 L2 命中数量不必相同。

## 另外准备的文件

- `l2_training_expansion.json`：本次 156 条训练示例的可读源文件，按意图分组，附有标签边界说明；实际导入执行第 1 步的 SQL。
- `l2_development_evaluations.json`：45 条开发校验问题，13 个业务意图各 3 条，加 6 条 OTHER。用于后续扩大校验或阈值校准，JSON 数组格式可用于发布界面，不导入训练表，也不是 200 条正式评测的替代品。

新增训练数据已通过格式、数量和归一化文本去重检查，与原来的 14 条发布校验、45 条开发校验及 200 条正式评测均无完全重复。训练与开发数据由同次整理产生，仍需人工复核；这些检查不能证明泛化效果。

SQL 插入、重复执行、哈希、源意图禁用和已有编号冲突保护，已在 MySQL 会话临时表夹具中验证。尚未向持久业务表导入本批数据，也未对新增样例进行真实模型验收。

已知原始种子把“交易手续费”归入 `SETTLEMENT_RULE`，而当前 L3 提示词与正式评测约定为 `PLATFORM_RULE`。本批没有覆盖或改写这条种子；后续正式报告前仍需统一该业务定义。
