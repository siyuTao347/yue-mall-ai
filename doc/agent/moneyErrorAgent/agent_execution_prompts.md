# 资金异常调查 Agent：实施提示词

## 1. 如何指定文件

建议给 Agent 明确的项目根目录、设计文件路径、本次实施阶段和验收标准。仅说“按照之前的方案实现”，新会话可能没有足够上下文；仅给目录，也容易把任务理解为继续写方案。

本机项目根目录是 `/Users/yu/IdeaProjects/mall-parent`。下面提示词中的相对路径均基于这个根目录；换机器或工作树时，只需替换根目录。无需在提示词里重复所有源文件，实施分册已有源码索引，Agent 应结合实际代码核对。

本功能的新表与种子 SQL 已放到 **`doc/sql/agent/moneyerrorAgent`**，详见[SQL 文件清单与执行顺序](../../sql/agent/moneyerrorAgent/README.md)。各表仍属于各自业务数据库。当前已生成文件但未执行迁移；实施时先检查已有脚本，避免重复生成同一套迁移。

## 2. 当前推荐：独立微服务改造提示词

当前已有部分 M0/M1 代码，同时存在[审查问题](implementation_review.md)。现在要实现独立服务，应使用本节，不从“完全空白的 M0/M1”重新开发。详细顺序为 R（修复事实源）→ S（独立运行时）→ A（A2A）→ C（客服闭环），见[任务清单](microservice_refactoring_tasks.md)。

收尾复核发现工作区已新增独立服务和客服桥接原型。下方“创建/新增”均按实际文件增量完善，先读主方案第 2.3 节，不重复建模块或覆盖有效修复。

### 2.1 一次明确整个改造目标

以下提示词直接授权开发 R/S/A/C；支付渠道和模型可以 Mock，不包含后续 O/W 的资金写能力。整体任务较大，Agent 应按批次完成与验证，并如实记录剩余工作。

```text
请在 /Users/yu/IdeaProjects/mall-parent 中实际实现资金异常调查 Agent 的独立微服务改造。请直接修改代码、必要 SQL、测试和开发记录，不要仅输出设计或可行性分析。

最终目标：新增一个可独立打包、启动、部署的 mall-money-agent-service；客服保留在 mall-agent-service，通过 A2A 委托调查；Money 通过受限 Dubbo 接口查询 Order/User，完成持久化调查、补证、报告和恢复，并与客服形成完整送达闭环。

先读取适用的 AGENTS.md（若有），再按顺序阅读以下文件：
1. /Users/yu/IdeaProjects/mall-parent/doc/agent/moneyErrorAgent/README.md
2. /Users/yu/IdeaProjects/mall-parent/doc/agent/moneyErrorAgent/independent_microservice_refactoring.md
3. /Users/yu/IdeaProjects/mall-parent/doc/agent/moneyErrorAgent/microservice_refactoring_tasks.md
4. /Users/yu/IdeaProjects/mall-parent/doc/agent/moneyErrorAgent/implementation_review.md
5. /Users/yu/IdeaProjects/mall-parent/doc/agent/moneyErrorAgent/implementation_progress.md（若存在，必须用代码与测试复核）
6. /Users/yu/IdeaProjects/mall-parent/doc/agent/moneyErrorAgent/development_design.md
7. /Users/yu/IdeaProjects/mall-parent/doc/agent/moneyErrorAgent/data_contracts_and_schema.md
8. /Users/yu/IdeaProjects/mall-parent/doc/agent/moneyErrorAgent/customer_agent_integration.md
9. /Users/yu/IdeaProjects/mall-parent/doc/agent/moneyErrorAgent/implementation_and_acceptance.md
10. /Users/yu/IdeaProjects/mall-parent/doc/sql/agent/moneyerrorAgent/README.md 及对应阶段 SQL

本次范围与顺序：
- R：核对并修复审查 R01—R12，先使鉴权、事实语义、分页、指纹、历史关联、幂等与审计可靠；不要仅因旧进度文档写“已完成”就跳过。
- S：创建独立服务工程、单数据源、任务与请求幂等、执行授权/受众隔离、快照、规则/有限 Graph、模型适配、报告投影、Worker/租约恢复，以及最小财务入口。
- A：实现真实 A2A 传输与 MoneyAgentClient，独立进程验证提交、任务/案件查询、补证、取消、重试和权限；不能使用同进程 Service 直调冒充 A2A。
- C：修复客服现有接入基础，完成 dispatch/binding、轮询与通知、槽位与 MONEY_BRIDGE、固定事实回复、卡片/补证、人工接管和端到端验收。
- 按 microservice_refactoring_tasks.md 的任务与门槛逐批执行，不提前实施 O/W，不开放 Agent 自动退款或补确认。

实施约束：
- 不参考 doc/agent/stage3_agent_development_design.md。
- 结合实际代码验证文件/方法/字段，不照搬不兼容设计；必要调整同步写明理由和替代验收。
- mall-money-agent-service 是单个部署应用，内部按包分层；不再拆成多个 Agent 服务，不把 Order/User 的业务 Mapper/支付/资金写入搬入 Money。
- Money 只连接 db_money_agent；客服、订单、资金表仍分别归 db_agent/db_order/db_user。
- 所有本功能新增 SQL 只放 /Users/yu/IdeaProjects/mall-parent/doc/sql/agent/moneyerrorAgent，使用 money_error_ 前缀；核对已有脚本，不重复创建同用途表。
- 允许使用现有 Mock 支付/退款/银行渠道，也允许 Mock 模型，不要求申请真实金融接口；Mock 必须通过相同的授权、证据、状态和报告校验。
- 保留已有无关改动；不要修改真实业务环境配置来通过测试，不执行已有业务环境迁移或自动发布意图库。
- 可以在可丢弃的隔离 MySQL 环境验证迁移、Mapper、唯一键和并发。渠道 Mock 与数据库集成验证是两回事。
- 资金事实不由模型猜测；渠道成功、平台入账、退款成功、买家实际到账分别表达。

交付要求：
- 实际运行受影响回归与任务清单中的关键失败验收，报告真实命令、数量和结果；未执行不标通过。
- 验证独立 java -jar、客服停机仍可财务调查、Money 重启恢复，以及 DROP_PAYMENT_NOTIFY 的客服全链路。
- 每批更新 doc/agent/moneyErrorAgent/implementation_progress.md，区分实现、单元验证、隔离数据库验证、独立进程验证、业务环境部署状态。
- 缺环境时继续完成不受影响的工作，记录具体阻塞；最终列明已完成任务、修改文件、SQL、验证证据和剩余事项。
```

### 2.2 每次只做一个可验收批次

希望逐批审查时，首次使用以下提示词；之后将“R 批次”替换为 S、A 或 C 及对应任务编号。每次仍先核对前置门槛，不因旧会话结束就重写全部实现。

```text
请在 /Users/yu/IdeaProjects/mall-parent 实施独立 Money 微服务改造，本次只完成 R 批次 R-00—R-06。

先读 /Users/yu/IdeaProjects/mall-parent/doc/agent/moneyErrorAgent/independent_microservice_refactoring.md、microservice_refactoring_tasks.md、implementation_review.md、implementation_progress.md（若存在）。后面三个文件与第一个位于同一目录。再按需读取该目录的数据契约/客服接入分册，以及 /Users/yu/IdeaProjects/mall-parent/doc/sql/agent/moneyerrorAgent/README.md 和本批 SQL。

以实际代码核对审查 R01—R12 是否仍存在，修复本批问题并增加对应失败回归。不要把旧的“M0/M1 已完成”当作验收依据，不重复修改已有效修复的代码，不提前实现 S/A/C/O/W。

允许 Mock 支付渠道与模型；不要要求真实银行接口。资金查询语义、权限、历史精确关联、只读边界和幂等必须真实实现，数据库验证使用可丢弃的隔离 MySQL。所有新增 SQL 放 doc/sql/agent/moneyerrorAgent，先核对已有脚本，不直接迁移已有业务环境。

保留无关工作区修改，不参考 doc/agent/stage3_agent_development_design.md。完成代码和可执行验证，更新 implementation_progress.md，报告已实现、已验证、待验证和下一批入口，不只输出建议。
```

| 后续提示词中的批次 | 指定任务 | 本次交付 |
| --- | --- | --- |
| S | S-01—S-07 | 独立可部署服务与调查运行时，财务独立入口 |
| A | A-01—A-03 | 协议、适配器、独立进程互通及故障验证 |
| C | C-01—C-05 | 客服、卡片、补证、接管及完整用户链路 |

## 3. 原始首次开发提示词：先完成 M0、M1

本节保留最初从零实施时的上下文。当前改造优先使用第 2 节，不将旧模板覆盖最新审查与独立微服务方案。

可以直接复制以下内容。这是一条开发指令，目标是实施两个明确阶段：

```text
请在项目 /Users/yu/IdeaProjects/mall-parent 中，实施资金异常调查 Agent 的 M0、M1 阶段。请直接进行必要的代码、SQL 和测试开发，不要只输出方案。

先阅读并遵守仓库适用的 AGENTS.md（如有），再依次阅读这些设计文件：
1. doc/agent/moneyErrorAgent/README.md
2. doc/agent/moneyErrorAgent/development_design.md
3. doc/agent/moneyErrorAgent/data_contracts_and_schema.md
4. doc/agent/moneyErrorAgent/customer_agent_integration.md
5. doc/agent/moneyErrorAgent/implementation_and_acceptance.md
6. doc/sql/agent/moneyerrorAgent/README.md 及其中对应本阶段的 SQL 文件

本次范围：
- M0：核对真实代码与数据库迁移基线，明确 DTO、状态、错误码、服务身份、委托授权和对象范围；完成相应公共契约与必要基础实现。
- M1：实现 Order 的受限支付/退款/对账事实查询与渠道只读观察；实现 User 的资金事务/流水查询和可信业务引用。
- 核对并验证已提供的本阶段 SQL，必要时补充结构增量；不要重复生成同名用途的脚本，不要提前实施 M2—M5。

约束：
- 不参考 doc/agent/stage3_agent_development_design.md。
- 以当前项目实际实现为依据。设计与源码有差异时先核对，再做必要调整，并同步修正文档、记录理由。
- 所有为本功能新增的 SQL 文件放到 doc/sql/agent/moneyerrorAgent，使用 money_error_ 前缀；按所属业务库和实际实施阶段生成增量，不修改已发布迁移来改变历史含义。
- SQL 文件目录不改变表所属数据库：调查运行时归 db_money_agent，客服桥接归 db_agent，订单和资金相关增量归 db_order/db_user。
- 不另建售后 Agent；本阶段不开放自动退款、自动补确认或对账关单。
- 查询必须校验业务归属，准确区分无记录、无权限、来源不可用和历史关联缺失；不能用模型猜测补齐资金事实。
- 保留工作区已有且与本任务无关的修改。
- 本次允许使用可丢弃的隔离测试数据库验证迁移与集成行为；不要将应用默认数据源当作测试库或直接迁移已有业务环境。

完成要求：
- 逐项对照实施分册的 M0/M1 验收门槛，运行相应编译、契约和集成检查。
- 给出修改文件、实际生成的 SQL 路径、执行过的检查及结果。
- 明确区分“SQL 已编写”“隔离测试已执行”“业务环境已迁移”，不得混报。
- 环境缺失时先完成不受影响的工作，具体记录未验证项，不宣称测试通过。
- 创建或更新 doc/agent/moneyErrorAgent/implementation_progress.md，记录阶段状态、证据、未完成项和下一步，使后续会话能够续接。
```

## 4. 原 M0—M5 阶段如何对应

M0/M1 验收完成后，每次明确一个阶段。基础顺序与本次范围如下：

| 本次阶段 | 主要开发范围 | 前置条件与验收重点 |
| --- | --- | --- |
| M2 | 独立 Money 服务、案件/任务持久化、规则、Graph、报告、Worker、A2A | M1 事实查询可用；不接客服也能调查，重试/重启可恢复 |
| M3 | 客服基础修复、可靠桥接、意图与槽位、回复校验、输入/进度卡片、人工接管 | M2 可联调；用户能完成发起、补证、查询和结果接收 |
| M4 | 源事件、主动调查、运营工作台、影子评测 | 用户发起闭环已通过；验证主动调查的事实准确性和业务价值 |
| M5 | 业务一致性修复、审批、受控补确认、幂等命令和执行复查 | 仅在本阶段明确要求下开展，满足设计中的相关一致性门槛 |

以下是继续实施 M2 的完整示例；实施 M3/M4/M5 时替换阶段与范围，仍保留文件、SQL 和验收约束：

```text
请继续在 /Users/yu/IdeaProjects/mall-parent 实施资金异常调查 Agent，本次完成 M2。

先阅读 doc/agent/moneyErrorAgent/README.md、implementation_progress.md（若存在），以及 development_design.md、data_contracts_and_schema.md、customer_agent_integration.md、implementation_and_acceptance.md。以上文件均位于 doc/agent/moneyErrorAgent 目录。

先用代码和测试记录核对 M0/M1 的真实完成状态；若存在阻塞 M2 的必要缺口，先补齐并记录。不要重复重写已完成工作，不要仅凭进度文档就认定已验收。

本次实现独立 mall-money-agent-service：案件/任务/请求幂等、执行授权与受众访问、事实快照、状态归一、规则、有限模型规划、持久化 Worker、报告与用户投影、A2A 调查及案件查询。满足 M2 的独立运行和故障恢复验收，不提前实施 M3—M5。

先阅读 doc/sql/agent/moneyerrorAgent/README.md 并核对已有 M2 SQL；必要的后续增量统一放到该目录，使用 money_error_ 前缀，不重复生成已提供的表。保留已有无关修改，不参考 doc/agent/stage3_agent_development_design.md，不开放资金写操作。

直接完成代码和必要测试；数据库验证使用可丢弃的隔离测试库，不迁移已有业务环境。完成后更新 implementation_progress.md，列明修改、SQL、实际验证结果、尚未验证的限制和下一阶段入口。
```

M3 建议依次完成客服接口/消息/权限基础修复、dispatch/binding 与 Worker、补证及通知、意图/Runner 接入，最后进行端到端验收和意图库发布。可用现有 Mock 的 DROP_PAYMENT_NOTIFY 场景验证第一条贯穿链路，再覆盖其他异常。

## 5. 如果另行要求执行数据库迁移

“开发这个功能”和“把 SQL 执行到某个已有数据库”应写清不同目标。实际迁移时提供目标环境、连接配置名称（不要在提示词中粘贴密码）和已经生成的具体脚本清单，不能使用目录通配符执行全部历史脚本。

已有脚本完成隔离数据库验证后，可使用以下模板，先替换尖括号中的内容：

```text
请在 /Users/yu/IdeaProjects/mall-parent，针对以下明确环境执行本功能的数据库迁移：
- 环境：<明确的开发/测试环境名称>
- 连接：<已配置的连接名称或配置文件引用，不包含明文密码>
- 允许修改的库：<本次涉及的具体库名>
- 脚本：<doc/sql/agent/moneyerrorAgent 下已生成并核对的具体文件路径，逐项列出>

先核对目标环境、前置表、字段和索引，确认脚本与当前结构兼容；按依赖顺序执行并验证结果。遇到同名表结构不符、连接无法确定或前置条件不满足时，停止受影响脚本并说明具体问题，不通过删除重建已有业务表来绕过。

本次仅执行列出的迁移，不自动发布客服意图库、开启资金修复能力或运行未列出的历史回填。记录实际执行的库、脚本、结果与未完成项，并更新 doc/agent/moneyErrorAgent/implementation_progress.md。
```
