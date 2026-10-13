# customerAgent 测评工具

这套工具调用项目的真实客服接口，采集运行轨迹、工具记录和转人工工单，再按独立测试数据评分。只需 Python 3.9+，Python 部分没有第三方依赖。

## 1. 已提供什么

| 文件/目录 | 内容 |
|---|---|
| [datasets/functional.jsonl](./datasets/functional.jsonl) | 240 个功能场景、283 轮对话；开发集/保留集各 120 个 |
| [datasets/l2-calibration.jsonl](./datasets/l2-calibration.jsonl) | 13 个业务意图各 10 题，另加 50 条 OTHER 负例，共 180 条 |
| [datasets/contracts-and-faults.jsonl](./datasets/contracts-and-faults.jsonl) | 42 个现有 JUnit 契约映射，以及 15 个需隔离环境的故障场景 |
| [scripts/run_evaluation.py](./scripts/run_evaluation.py) | 用例校验、预览、真实 API 执行、确定性/语义评分、报告 |
| [scripts/run_contract_tests.py](./scripts/run_contract_tests.py) | 调用现有 Maven 测试，只采信本次新生成的 Surefire 报告 |
| [scripts/prepare_l2_release.py](./scripts/prepare_l2_release.py) | 校验意图覆盖/查重，导出已有发布接口所需的请求体 |
| [scripts/compare_runs.py](./scripts/compare_runs.py) | 相同数据与 fixture 下的基线/候选版本成对比较 |
| [scripts/build_datasets.py](./scripts/build_datasets.py) | 从维护的场景定义可重复生成两份 JSONL |
| [fixtures/template.json](./fixtures/template.json) | 测试用户、订单、售后、知识和开关的独立期望快照模板 |
| [fixtures/knowledge](./fixtures/knowledge) | 5 份仅供隔离环境使用的合成规则文本 |
| [graders/semantic_rubric.md](./graders/semantic_rubric.md) | 可选模型裁判的评分标准 |
| [tests/test_evaluation.py](./tests/test_evaluation.py) | 评测工具自身的离线回归测试 |

功能用例覆盖问候边界、订单、售后、规则、同意图双订单、多轮补槽、投诉/人工、权限攻击、会话恢复和幂等。意图实例按 code 与业务槽位一对一匹配，不能把两个 ORDER_STATUS 去重成一个。

这是可运行的初始评测集。题目和合成 gold 仍需业务人员审核；它们不是已验证通过的生产基准。部分 Mock 不理解的语义题可能失败，不能据此评价真实模型；真实模型同样必须单独运行。

## 2. 现在需要提供模型 API 信息吗

**编写脚本、校验数据、运行离线回归都不需要。** 真正执行时分为两组配置：

| 目标 | 需要的配置 |
|---|---|
| Mock 客服 API 冒烟 | 服务地址、普通测试用户 JWT、管理员 JWT；订单场景还需对应 fixture |
| 真实客服模型测评 | 在 mall-agent-service 配置 CUSTOMER_SERVICE_PROVIDER/API_KEY/MASTER_MODEL/RESPONSE_MODEL，以及实际 Embedding 配置 |
| 规则答案的语义裁判 | 本工具配置 CUSTOMER_EVAL_JUDGE_URL、CUSTOMER_EVAL_JUDGE_MODEL、CUSTOMER_EVAL_JUDGE_API_KEY |

被测客服目前的真实 Chat provider 是 DASHSCOPE。裁判脚本支持 `messages → choices[0].message.content` 的 Chat Completions 兼容 HTTP 协议，不绑定某一家模型厂商。裁判与客服可以使用不同模型；填写裁判配置不会更改被测客服。

`CUSTOMER_EVAL_JUDGE_URL` 填完整请求端点，例如服务商给出的、以 `/chat/completions` 结尾的地址。API Key 写进本地 `.env`，不要提交 Git 或贴在聊天中。若接口不是这个协议，需要增加对应适配器，不能只改 URL 后假定兼容。

裁判只评价真实客服返回的 answer/intentResults，不重新生成客服答案。缺少裁判配置时，适用的 live 语义检查标为 `NOT_EVALUATED`；不会用默认高分跳过。

## 3. 无服务、无密钥也可以先验证

在本目录运行：

```bash
make validate
make test
make dry-run
```

分别检查数据可重复生成、用例格式/引用路径、评分器与执行器回归，以及展示将运行的全部用例。不会访问客服或外部模型。

运行已有 Java 契约测试：

```bash
python3 scripts/run_contract_tests.py --list
python3 scripts/run_contract_tests.py
```

默认 Maven 离线模式，需要本地 JDK 21、Maven 及已缓存的项目依赖。依赖未缓存时可显式加 `--online`。该脚本验证 42 项已有 Java 测试，不把它们等同于真实数据库/网络故障验证。

## 4. 准备真实 API 冒烟

```bash
cp config/env.example .env
cp fixtures/template.json fixtures/local.json
```

编辑 `.env`，至少配置：

```dotenv
CUSTOMER_EVAL_BASE_URL=http://127.0.0.1:8085
CUSTOMER_EVAL_MODE=mock
CUSTOMER_EVAL_BUYER_TOKEN=Bearer <测试买家JWT>
CUSTOMER_EVAL_ADMIN_TOKEN=Bearer <测试管理员JWT>
```

8085 是当前 Agent 服务默认端口；也可填测试网关地址。`.env` 解析器按字面读取 KEY=value，不执行 shell、不展开变量，并且已有进程环境变量优先。

先运行不依赖业务数据的一条问候：

```bash
python3 scripts/run_evaluation.py --env-file .env \
  --case-id GREETING-001 --mode mock --judge off
```

创建会话、发消息使用普通用户 token；读取对应 run/tool-calls 使用管理员 token。问候应走 L1、工具记录为空。HTTP 200 或 run=SUCCEEDED 不直接视为成功。

同时跑几条可用场景：

```bash
python3 scripts/run_evaluation.py --env-file .env \
  --case-id GREETING-001 --case-id GREETING-009 --case-id HANDOFF-001 \
  --mode mock --judge off
```

这会在测试系统真实创建会话、日志和人工工单。请选择专用测试账号和测试环境；脚本不会自动删除业务数据或激活发布。

## 5. 配置订单、售后与知识 fixture

模板中的编号和状态只是示例，所有业务数据默认 `ready=false`。脚本不会为你伪造用户、签发 JWT 或插入真实订单。

1. 通过项目已有业务流程或隔离库夹具创建买家、卖家、无关用户、管理员。
2. 在 `.env` 设置对应 JWT，在 `fixtures/local.json` 填写每个账号真实 `user_id`。同一个账号的多个 token 必须使用同一个 user_id，以便用户级调度正确。
3. 准备买家/卖家共同拥有的订单 a、b，以及无关用户拥有的订单 c。把 DTO 对应字段和 timeline 的**实际值**写入快照，核对 owners，才将该条 `ready` 改为 true。
4. 售后 a 对应订单 a，售后 c 对应订单 c。分别填写真实售后号、关联号、状态和时间。冲突用例故意组合订单 b 与售后 a。
5. live 规则评测要导入并启用公开知识、等待向量 READY，再填写每类知识的真实 docNo、版本、父块 chunkNo、内容及 required_points。
6. 合成规则只适合隔离语料实验。评测实际平台政策时，改用正式审核文档和题目对应的 gold 要点；不要把示例文本导入生产政策库。
7. 记录 manifest 中的应用构建、意图库发布号/hash、知识快照与审核状态。确认售前关闭时，将 features.pre_sale_disabled.ready 置为 true。

fixture 的 `ready` 是人工确认前置条件的标记，不是自动证明数据真实。脚本会继续把 API facts、工具返回与快照比较；实际不一致会失败。所有返回的订单/售后 facts 都必须属于当前 actor 的 owners 范围。

时间字段按当前 DTO 的字符串形式填写，例如 `2026-10-13T10:00`。timeline 按服务返回的顺序填写。`null` 表示业务上确实没有该值，不表示“懒得填”。订单与售后 fixture 必須包含完整 DTO 字段，不能只填编号。

知识引用使用 `docNo + docVersion + chunkNo + intentInstanceId` 检查，不将不同实例都可能出现的 S1 当成全局唯一编号。引用内容必须来自独立快照，同时由语义裁判核验是否支持问题所需结论。

## 6. 执行完整场景与重复测评

先检查执行范围：

```bash
python3 scripts/run_evaluation.py --fixtures fixtures/local.json \
  --suite all --split dev --mode live --dry-run
```

运行 Mock API 集成：

```bash
python3 scripts/run_evaluation.py --env-file .env --fixtures fixtures/local.json \
  --suite all --split dev --mode mock --judge off
```

运行真实模型与裁判：

```bash
python3 scripts/run_evaluation.py --env-file .env --fixtures fixtures/local.json \
  --suite all --split holdout --mode live --judge required --trials 3
```

常用筛选：`--category multi`、`--category security`、`--case-id MULTI-021`、`--limit 10`。类别筛选通常配合 `--suite all`；默认 smoke 只选 10 个入口场景。`--case-id` 可重复且覆盖 smoke 限制。修改数据定义后运行 `make datasets`，再执行 `make validate`。

每个 trial 新建会话，每条消息使用新的 clientMessageId；只有显式 replay 用例重复原标识。网络失败不会被自动重试到成功。重复 trial 的统计同时给出平均成功率、至少一次成功和全部 k 次成功。

默认每用户消息间隔 15.1 秒，同身份的完整场景串行，适应当前每分钟最多 5 次 run 的限制。`--workers` 可让不同账号并行；同账号增加 workers 不会增加吞吐。扩大负载前需扩展独立测试账号、对应 owners 和数据集 actor_ref。`--interval 0` 仅适合本地模拟或明确的限流实验，正常质量评测不要关闭节流。

240 题含多轮且受服务限流，可能需要较长时间。脚本每完成一个 trial 就追加保存结果；中断后保留已完成证据，但没有完整 summary 的目录不能作为通过报告。

## 7. L2 发布校准

```bash
python3 scripts/prepare_l2_release.py \
  --training-examples fixtures/release-examples.json \
  --output outputs/l2-request.json
```

`release-examples.json` 自行从待发布示例导出，为字符串数组。脚本规范化查重，验证 3～200 条及意图覆盖，生成 `{ "evaluations": [...] }`。可用 `--intent-codes ...` 指定目标版本实际启用的业务意图列表；数据集必须与其一致，不能默默忽略多出的类别。

不传训练示例也能导出请求体，但输出明确提示训练泄漏检查未执行。还需人工检查语义近重复。文件只是发布 API 的请求体，脚本不会调用创建/激活/撤销接口。

这 180 条是**初始校准候选**。现有发布逻辑要求提交题全部通过才 READY；尚未经过模型运行与人工审核，不能保证该请求会通过。L2 中的 OTHER 表示“不应直接路由”，不代表完整客服最终应回答 OTHER。

## 8. 输出与返回码

默认输出到 `outputs/<UTC时间>-<随机后缀>/`；`--output` 可指定新目录，已存在目录会拒绝覆盖。

| 文件 | 用途 |
|---|---|
| manifest.json | 数据、选择范围、fixture、执行器、裁判 rubric 的 hash 及有效参数 |
| raw-results.jsonl | 每个场景的请求、实际响应、已解码 trace/tool-call、工单、逐轮判定；保存前脱敏 |
| checks.jsonl | 逐断言 PASS/FAIL/NOT_EVALUATED、原因及期望/实际值 |
| judge-results.jsonl | 裁判输出、错误、模型、usage 和耗时 |
| summary.json | 门禁、按类别的成功率、路由实例 Micro/Macro-F1、重复稳定性、延迟与已报告 token |
| report.md | 可阅读的结果及失败列表 |

`raw-results.jsonl` 已包含归一化字段，不再另写一份重复的 normalized.jsonl。未对应用写库做直接采样的指标明确保留缺口。费用不凭空估算，token 统计标注为“服务已报告值”；没有完整用量与价格快照时 cost=null。

返回码：0=本次选定 API 范围通过；1=门禁失败；2=配置错误或必需证据不足。未配置账号、fixture 未就绪、管理员轨迹不可读、裁判失败均不能算通过。

`summary.status` 是当前选定 API 范围的结论；`release_status` 保持 `NOT_EVALUATED`，因为这套 HTTP 执行器不能代替真实数据库取消、业务表无写入、探针完整性、人工复核和代表性性能专项。

基线比较：

```bash
python3 scripts/compare_runs.py outputs/baseline outputs/candidate \
  --output outputs/comparison.json
```

数据、fixture、选择范围、trial 数、模式、裁判及门槛不同会拒绝比较。不对缺失 trial 做交集截断，也不把未评分当成失败或提升。版本比较给出成对结果，不冒称统计显著。

## 9. 故障专项与当前边界

`contracts-and-faults.jsonl` 中的 42 个 `junit` 项可直接由脚本执行；15 个 `isolated_environment` 项给出注入条件、预期结果和必需证据，包括丢竞争向量、额外向量、版本污染、撤销、底层取消、90 秒预算、租约恢复、限流和业务表快照。

当前客服没有公开的故障注入 API，因此这 15 项不会伪装成已自动测试。需要隔离数据库/模型端点/调用探针的适配器；Java mock 的元数据测试通过也不代表真实 PG 完整性、HTTP/JDBC 取消通过。

当前版本还未自动执行：真实故障注入、生产容量测算、统计置信区间、RAGAS、用户模拟器、前端 UI 测试、人工 gold 审核、评测结果入业务表。它们保留在[测评方案](./customer_agent_evaluation_plan.md)中，不影响已实现的 API 用例和离线契约工具运行。

首次正式评测建议先人工审查题目分布与 gold，再冻结版本。虽然文件按 family 避免跨 split 重复，但初始 holdout 有更多多轮/边界任务，不能把开发集与保留集的分数差直接解释为模型退化。

## 10. 后续维护

- 新增场景：修改 build_datasets.py 中的定义，保持 family 整体归属一个 split；重新生成并校验。
- 新增断言：同时扩展 dataset.py 的允许字段与 grading.py，再添加一个应拒绝的反例测试。
- 改评分标准：更新 gates.json 或 rubric，冻结后再跑候选版本；不要看结果后调低门槛。
- 线上问题回流：脱敏、复现、审核 gold 后进入开发/回归集，不反复用保留集调参。
- 密钥、真实账号数据和运行产物已由本目录 .gitignore 排除；分享结果前仍需确认没有真实业务隐私。

方法学习见 [Agent 测评教程](./agent_evaluation_tutorial.md)，完整设计与后续范围见 [customerAgent 测评方案](./customer_agent_evaluation_plan.md)。
