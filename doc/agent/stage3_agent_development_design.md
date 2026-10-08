# 虚拟资产商城阶段三 Agent 能力设计与实施方案

> 文档状态：设计稿，未开始实现。  
> 前置条件：阶段一担保交易闭环、阶段二规则风控与案件闭环已完成。  
> 阶段目标：把 Agent 做成业务系统能力，而不是聊天玩具。Agent 负责读数据、查知识、汇总证据、生成可解释建议；资金、处罚、仲裁结论仍由人工和原业务流程确认。

---

## 1. 阶段定位

### 1.1 核心目标

阶段三要解决的是运营效率问题：

1. **仲裁助手**：把订单、交付证据、售后留言、信用和风控信息汇总成一份可判断的材料，减少人工翻库查表。
2. **风控调查助手**：把阶段二的事件、决策、关系边和案件记录整理成调查报告，辅助风控人员判断。
3. **智能客服**：回答当前用户自己的订单状态、平台规则、售后流程，并在需要时转人工。
4. **商品发布助手**：帮卖家生成标题、描述和风险声明草稿，不直接提交商品。
5. **Agent 工程**：落地 Tool Calling、RAG、权限隔离、Trace、评估和降级，展示后端与 Agent 开发的结合。

### 1.2 设计原则

1. **业务系统优先**：Agent 服务挂掉，商品、订单、支付、售后、提现、风控主链路必须照常运行。
2. **只读优先**：Agent 默认没有业务写权限，只允许写 Agent 自己的会话、运行、草稿和审计数据。
3. **人工确认**：退款、放款、提现、处罚、仲裁结论不能由 Agent 直接执行。
4. **工具边界**：Agent 不直连业务数据库，只调用各领域暴露的只读 Dubbo 接口。
5. **证据可追溯**：每次回答都能看到模型、提示词版本、检索来源、工具调用和输出结构。
6. **本地可运行**：默认使用 Mock 模型和关键词检索；OpenAI 兼容接口、Embedding、向量检索均为可选增强。
7. **不过度设计**：不做多 Agent 协作、不做复杂 Planner、不做自动执行链、不引入重型 Agent 平台。

### 1.3 阶段三明确不做

| 不做项 | 原因 |
|---|---|
| Agent 直接退款 / 放款 / 提现 / 扣保证金 | 资金动作必须由业务服务和人工流程执行 |
| Agent 自动处罚账号或冻结商家 | 风控命令仍由人工确认后下发 |
| Agent 自动仲裁结案 | 仲裁建议只能辅助人工判断 |
| 多 Agent 自主协作 | 学习项目会显著增加复杂度，收益低 |
| 复杂自主 Planner | 用确定性 Agent 编排足够，避免不可控调用链 |
| 强制依赖真实大模型 | 默认 Mock 可运行，便于本地开发和面试演示 |
| 向量数据库 | 知识库规模小，先做本地向量或关键词检索 |
| 全量日志进入模型 | 只传权限内、脱敏后的最小上下文 |

---

## 2. 总体架构

### 2.1 服务拓扑

新增 `mall-agent-service` 和独立数据库 `db_agent`。

```mermaid
flowchart TD
    U[买家 / 卖家] --> FE[React 前端]
    A[客服 / 风控 / 管理员] --> AdminConsole[管理后台]

    FE --> Agent[mall-agent-service]
    AdminConsole --> Agent

    Agent --> Session[会话与消息]
    Agent --> Orchestrator[Agent Orchestrator]
    Agent --> Guardrail[权限与脱敏]
    Agent --> Rag[知识库 RAG]
    Agent --> Tools[Tool Registry]
    Agent --> Trace[Run 与 Tool Trace]
    Agent --> Draft[建议与草稿]

    Tools --> OrderAPI[order 只读接口]
    Tools --> RiskAPI[risk 只读接口]
    Tools --> UserAPI[user 只读接口]
    Tools --> ItemAPI[item 只读接口]

    Agent --> LLM[模型适配层]
    LLM --> Mock[MockLlmClient]
    LLM --> OpenAI[OpenAI 兼容接口]

    Agent --> AgentDB[(db_agent)]
    OrderAPI --> OrderDB[(db_order)]
    RiskAPI --> RiskDB[(db_risk)]
    UserAPI --> UserDB[(db_user)]
    ItemAPI --> ItemDB[(db_item)]
```

### 2.2 服务职责

| 服务 | 阶段三新增职责 | 明确不负责 |
|---|---|---|
| `mall-agent-service` | 会话、编排、RAG、工具调用、模型适配、Trace、草稿、转人工记录、评估 | 直接修改商品、订单、商家、资金、风控案件状态 |
| `mall-order-service` | 暴露订单、售后、交付证据的 Agent 只读查询接口 | 执行 Agent 指令 |
| `mall-risk-service` | 暴露案件、决策、事件摘要、关系图谱的 Agent 只读查询接口 | 根据 Agent 输出自动处罚 |
| `mall-user-service` | 暴露用户、商家、信用、提现摘要的 Agent 只读查询接口 | 根据 Agent 输出直接动账 |
| `mall-item-service` | 暴露商品、类目、发布上下文的 Agent 只读查询接口 | 直接上架 Agent 生成商品 |
| `mall-api` | Agent 请求 / 响应 DTO、只读查询接口、公共枚举 | Agent 业务实现 |
| `mall-frontend` | 智能客服入口、仲裁助手面板、风控案件助手、Trace 查看 | 直接调用业务数据库 |

### 2.3 跨服务调用原则

1. `mall-agent-service` 只访问 `db_agent`。
2. 业务数据一律通过领域服务的只读 Dubbo 接口获取。
3. 工具调用必须携带 `AgentActor`，包含 `userId`、`authRole`、服务端派生的 `merchantId` 和请求来源。
4. 权限校验放在领域服务工具实现中，Agent 层的权限判断只是第一道防线。
5. Agent 不获得 `FundDubboService` 引用。
6. Agent 不消费 `risk-command-topic`，也不发布业务命令。
7. Agent 输出的建议只写入 `t_agent_draft`，由人工在原有业务页面确认。

### 2.4 运行模式

| 模式 | 说明 | 用途 |
|---|---|---|
| `MOCK` | 使用确定性 Mock 模型和关键词检索，不访问外网 | 默认开发、单元测试、本地演示 |
| `OPENAI_COMPATIBLE` | 使用 OpenAI 兼容 Chat Completions 接口 | 有模型环境时展示真实效果 |
| `EMBEDDING_ENABLED` | 启用 Embedding 和本地向量检索 | 增强 RAG 召回，不是必选 |

默认配置：

```yaml
agent:
  provider: MOCK
  openai-compatible:
    base-url: ${AGENT_BASE_URL:}
    api-key: ${AGENT_API_KEY:}
    chat-model: ${AGENT_CHAT_MODEL:}
    embedding-model: ${AGENT_EMBEDDING_MODEL:}
    timeout-ms: 30000
  retrieval:
    mode: KEYWORD
    top-k: 5
  run:
    max-tool-rounds: 3
    max-tool-calls: 8
    max-input-tokens: 12000
```

不把模型密钥写入代码、SQL、前端或 Markdown 示例，只允许环境变量或本地未提交配置。

### 2.5 Agent 框架选型

推荐使用轻量编排，不引入重型 Agent 平台：

| 能力 | 方案 |
|---|---|
| 模型调用 | 自定义 `LlmClient` + OpenAI 兼容 HTTP 客户端 |
| Tool Calling | 自定义 `ToolRegistry`，工具元数据、权限、参数校验在服务端统一管理 |
| RAG | 自定义 `RetrievalService`，关键词检索 + 可选本地向量检索 |
| 结构化输出 | 提示词约束 + JSON Schema 校验 + 失败降级 |
| Trace | 自建 `t_agent_run` / `t_agent_tool_call` |
| 评估 | 固定测试集 + 自动断言 + 人工复核 |

可选升级：

1. 如果项目后续升级 Spring Boot 版本且版本兼容，可以引入 Spring AI。
2. 如果希望减少手写模型调用代码，可以引入 LangChain4j 的 OpenAI 兼容客户端。
3. 无论使用框架与否，`LlmClient`、`ToolRegistry`、`RetrievalService` 的接口保持不变。

这样做的目的不是排斥框架，而是避免阶段三被框架版本、自动 Planner 和隐藏调用链绑架。核心 Agent 工程能力必须显式、可测试、可观测。

---

## 3. Agent 能力地图

| Agent | 使用者 | 核心能力 | 优先级 | 输出形态 |
|---|---|---|---:|---|
| 仲裁助手 | 客服 / 管理员 | 订单时间线、证据汇总、双方主张、规则引用、仲裁建议 | P0 | 结构化建议，人工确认 |
| 风控调查助手 | 风控 / 管理员 | 案件摘要、命中规则解释、关系证据、下一步调查建议 | P0 | 调查报告，人工处理 |
| Agent 基础设施 | 系统 | 会话、运行、工具、权限、Trace、降级 | P0 | 内部能力 |
| 知识库 RAG | 所有 Agent | 平台规则、售后规则、风控规则、FAQ 检索与引用 | P0 | 引用来源 |
| 智能客服 | 买家 / 卖家 | 自己订单查询、规则解释、售后指引、转人工 | P1 | 对话回答 |
| 商品发布助手 | 卖家 | 标题、描述、风险声明草稿 | P1 | 草稿，卖家确认后走原审核 |
| Agent 评估 | 开发 / 管理员 | 固定用例、指标统计、回归报告 | P1 | 评估报告 |
| 经营分析助手 | 商家 / 管理员 | 销售、退款、纠纷、转化分析 | P2 | 报告 |
| 平台运营助手 | 管理员 | 日报、周报、风控报告 | P2 | 报告 |
| 多 Agent 协作 | 系统 | 客服、仲裁、风控协同 | P2 | 不建议阶段三做 |

推荐实现顺序：

```text
基础设施 -> RAG -> 仲裁助手 -> 风控调查助手 -> 智能客服 -> 商品发布助手
```

智能客服是明确要做的能力，但优先级低于仲裁和风控。原因是仲裁与风控更依赖项目自身的业务数据和阶段二成果，更能体现这个项目的差异化工程价值。

---

## 4. Agent 编排设计

### 4.1 运行状态机

```text
CREATED -> VALIDATING -> RETRIEVING -> TOOL_RUNNING -> GENERATING -> VALIDATING_OUTPUT -> COMPLETED
```

异常状态：

| 状态 | 说明 |
|---|---|
| `BLOCKED` | 权限不足、触发安全规则或超出频率限制 |
| `FAILED` | 模型、工具或输出解析失败 |
| `CANCELLED` | 用户或管理员主动取消 |

状态只保存在 `t_agent_run`，不修改业务状态。

### 4.2 标准执行流程

```text
接收请求
  -> 校验登录、角色、Agent 类型、频率和输入长度
  -> 创建或复用 session
  -> 创建 run 并生成 runNo
  -> 组装 AgentActor
  -> 加载当前 Agent 的工具白名单
  -> 检索知识库片段
  -> 按需调用只读工具
  -> 对工具结果统一脱敏
  -> 组装提示词
  -> 调用 Mock 或真实模型
  -> 解析结构化 JSON
  -> 执行输出 Guardrail
  -> 保存回答、草稿、Trace
  -> 返回结果
```

### 4.3 编排限制

| 限制 | 默认值 | 说明 |
|---|---:|---|
| 单次工具轮数 | 3 | 防止工具循环 |
| 单次工具调用总数 | 8 | 包括失败调用 |
| 单个工具超时 | 800ms | 只读查询不应拖慢 Agent |
| 模型调用超时 | 30s | 同步请求上限 |
| 用户消息长度 | 2000 字符 | 超长输入直接拒绝 |
| 会话历史条数 | 10 | 只保留最近上下文 |
| 知识片段数 | 5 | 控制上下文和成本 |
| 用户每小时请求数 | 20 | 固定窗口限流 |
| 管理员每小时请求数 | 60 | 仲裁和风控调查允许更高 |

超过限制时返回明确错误，不静默截断业务事实。

### 4.4 上下文组装

上下文只包含四类内容：

1. 系统提示词和输出格式。
2. 当前用户身份、角色和 Agent 类型。
3. 权限允许且已脱敏的业务数据。
4. 带来源编号的知识库片段。

不进入上下文：

1. 卡密明文。
2. 密码、JWT、支付凭证。
3. 完整身份证、银行卡、真实提现账号。
4. IP 和设备明文。
5. 与本次请求无关的用户数据。
6. 数据库连接、SQL、内部异常栈。

### 4.5 幂等与重试

1. 每次运行生成唯一 `runNo`。
2. 同一 `runNo` 重试时，如果请求哈希一致，返回已有结果。
3. 请求哈希不一致时返回 `AGENT_RUN_CONFLICT`。
4. 模型调用失败默认不自动重试，避免重复消耗 Token。
5. 只读工具调用可以按工具配置重试一次。
6. 用户重新点击“生成”时创建新 `runNo`，保留历史对比。

---

## 5. Tool Calling 设计

### 5.1 工具注册模型

每个工具必须显式注册：

```json
{
  "toolName": "queryOrder",
  "description": "查询权限范围内的订单状态、资金状态和交付状态",
  "agentTypes": ["CUSTOMER_SERVICE", "ARBITRATION"],
  "allowedRoles": ["USER", "ADMIN"],
  "inputSchema": {
    "type": "object",
    "required": ["orderNo"],
    "properties": {
      "orderNo": { "type": "string", "maxLength": 64 }
    }
  },
  "readOnly": true,
  "timeoutMs": 800,
  "sensitiveFields": ["cardSecret", "paymentCredential", "ip", "deviceHash"]
}
```

注册后工具才能被编排器调用。没有注册的工具，即使模型在输出中要求调用，也一律忽略并记录 `BLOCKED_TOOL`。

### 5.2 P0 只读工具

| 工具 | 调用服务 | 说明 |
|---|---|---|
| `queryOrder` | order | 查询订单状态、金额、时间、结算、售后摘要 |
| `queryOrderTimeline` | order | 查询订单状态流转和关键操作时间 |
| `queryDeliveryEvidence` | order | 查询脱敏后的交付凭证和查看记录 |
| `queryDispute` | order | 查询售后争议、双方主张和留言摘要 |
| `queryMerchantCredit` | user | 查询商家完成率、退款率、纠纷率、等级 |
| `queryRiskDecision` | risk | 查询订单或商品关联的风控决策摘要 |
| `queryRiskCase` | risk | 查询风控案件、处理记录和当前状态 |
| `queryRelations` | risk | 查询用户一度或二度关系，最大深度 2 |
| `searchKnowledge` | agent | 检索平台规则、售后规则和 FAQ |

### 5.3 P1 工具

| 工具 | 调用服务 | 说明 |
|---|---|---|
| `queryMyOrders` | order | 智能客服查询当前用户订单列表，最多 10 条 |
| `queryItem` | item | 商品发布助手查询类目、属性和发布规范 |
| `queryCategoryRule` | item | 查询类目准入、交付方式和保证金要求 |
| `createHandoff` | agent | 记录转人工请求，不修改业务工单 |

`createHandoff` 只写 `db_agent.t_agent_handoff`，不是业务工单系统。管理员可以在 Agent 后台看到待处理转人工记录。

### 5.4 权限矩阵

| 工具 | 买家 | 卖家 | 客服 | 风控 | 管理员 |
|---|---:|---:|---:|---:|---:|
| 查询自己的订单 | 是 | 是 | 是 | 是 | 是 |
| 查询他人订单 | 否 | 否 | 是 | 是 | 是 |
| 查询自己商家信用 | 否 | 是 | 是 | 是 | 是 |
| 查询他人商家信用 | 否 | 否 | 是 | 是 | 是 |
| 查询交付证据 | 关联订单 | 关联订单 | 是 | 是 | 是 |
| 查询风控决策 | 关联订单摘要 | 关联订单摘要 | 是 | 是 | 是 |
| 查询风控案件 | 否 | 否 | 否 | 是 | 是 |
| 查询关系图谱 | 否 | 否 | 否 | 是 | 是 |
| 检索知识库 | 是 | 是 | 是 | 是 | 是 |
| 执行资金动作 | 否 | 否 | 否 | 否 | 否 |
| 执行处罚动作 | 否 | 否 | 否 | 否 | 否 |

普通用户询问他人订单时，工具返回 `AGENT_PERMISSION_DENIED`，Agent 只能回答“无权查询该订单”，不能透露订单是否存在。

权限矩阵中的“买家、卖家、客服、风控”是业务视角。当前工程落地时：

1. 买家、卖家由 `USER` 结合订单归属或商家归属判断。
2. 客服、风控入口先统一要求 `ADMIN`。
3. 不为了阶段三新增认证角色。

### 5.5 工具返回结构

```json
{
  "success": true,
  "data": {
    "orderNo": "T202609290001",
    "orderStatus": "CONFIRMED",
    "escrowStatus": "WAIT_SETTLE",
    "settleAvailableTime": "2026-09-30 10:00:00",
    "disputeStatus": "NONE"
  },
  "maskedFields": ["buyerPhone", "cardSecret"],
  "elapsedMs": 42
}
```

失败结构：

```json
{
  "success": false,
  "errorCode": "AGENT_PERMISSION_DENIED",
  "message": "无权查询该订单",
  "elapsedMs": 5
}
```

工具失败不代表 Agent 运行失败。Agent 可以在回答中说明“未能获取某类数据”，但不得编造数据。

### 5.6 工具安全要求

1. 工具实现必须使用参数化 DTO，不允许拼接 SQL。
2. 工具层必须做角色和资源归属校验。
3. 返回字段使用白名单，不是先查全量再让前端脱敏。
4. 所有工具调用写 `t_agent_tool_call`。
5. 工具输出进入模型前二次脱敏。
6. 工具异常信息只记录错误码，不把内部异常栈发给模型。
7. 禁止提供任何资金、退款、放款、提现、处罚、修改订单状态工具。

---

## 6. 知识库 RAG 设计

### 6.1 知识来源

| 类型 | 内容 | 来源 |
|---|---|---|
| `RULE` | 交易流程、结算冷却期、手续费、提现规则 | 阶段一设计文档中已确定规则 |
| `POLICY` | 商品准入、禁售范围、风险声明要求 | 平台规则配置 |
| `AFTER_SALE` | 售后时效、证据要求、仲裁口径 | 仲裁流程设计 |
| `RISK` | 风险等级、动作语义、申诉口径 | 阶段二规则说明 |
| `FAQ` | 支付、发货、确认、退款、提现常见问题 | 手工整理 |

阶段三不把历史订单、风控事件、用户留言自动灌入知识库，避免隐私泄露和错误结论被二次检索。

### 6.2 文档处理流程

```text
管理员上传或编辑知识文档
  -> 保存 t_knowledge_document
  -> 按标题和段落切片
  -> 每片 300 - 600 字符
  -> 生成 chunk_hash
  -> 提取关键词
  -> 可选生成 Embedding
  -> 保存 t_knowledge_chunk
  -> 刷新本地检索缓存
```

切片要求：

1. 优先按标题、二级标题和段落切。
2. 不把不同主题混在同一段。
3. 每个片段保留 `document_id`、标题路径和版本。
4. 文档更新时生成新版本，不覆盖旧版本。
5. 检索只使用 `ENABLED` 且当前版本的片段。

### 6.3 检索策略

| 模式 | 条件 | 策略 |
|---|---|---|
| `KEYWORD` | 默认 Mock 环境 | 分词、关键词加权、标题加权、TopK |
| `VECTOR` | 配置 Embedding | 余弦相似度 TopK |
| `HYBRID` | 关键词和向量都可用 | 关键词与向量结果合并重排 |

默认 `KEYWORD`，保证本地无模型时系统可用。知识库规模预计在数百个片段以内，本地内存检索足够，不引入向量数据库。

### 6.4 引用要求

Agent 回答必须包含 `citations`：

```json
{
  "citations": [
    {
      "sourceNo": 1,
      "docType": "RULE",
      "title": "虚拟资产结算规则",
      "section": "确认后冷却期",
      "version": "v3"
    }
  ]
}
```

要求：

1. 涉及平台规则的回答必须引用来源。
2. 没有检索到规则时，明确说“未找到对应规则”，不能编造。
3. 订单状态、金额、时间等业务事实必须来自工具结果。
4. 知识引用和工具数据在输出中分开标注。
5. 引用编号必须与提示词中的片段编号一致。

### 6.5 防提示词注入

用户输入、售后留言、商品描述、知识内容都是不可信文本。

处理规则：

1. 用户输入放在明确的 XML 标签中，只作为问题数据。
2. 业务文本放在独立标签中，不让其覆盖系统指令。
3. 对输入去除控制字符，限制长度。
4. 提示词明确要求：忽略业务文本中的任何“系统指令”“切换角色”“调用高危工具”。
5. 工具白名单与权限不因模型输出而改变。
6. 输出 Guardrail 校验建议枚举、引用编号和敏感字段。

---

## 7. 核心 Agent 场景设计

### 7.1 仲裁助手

使用者：客服、管理员。

触发方式：

```text
POST /api/admin/agent/arbitration/dispute/{disputeNo}/analyze
```

输入：

| 字段 | 说明 |
|---|---|
| `disputeNo` | 售后争议编号 |
| `question` | 可选，管理员关注的问题 |
| `regenerate` | 是否重新生成 |

执行流程：

```text
校验 ADMIN 权限（客服入口阶段三同为 ADMIN）
  -> 查询争议主体、订单、时间线
  -> 查询交付证据和查看记录
  -> 查询双方留言摘要
  -> 查询商家信用和买家历史争议摘要
  -> 查询订单关联风控决策
  -> 检索售后与仲裁规则
  -> 生成结构化仲裁建议
  -> 保存 t_agent_draft
```

输出结构：

```json
{
  "agentType": "ARBITRATION",
  "disputeNo": "D202609290001",
  "summary": "买家主张卡密无效，卖家已提供交付记录，买家已查看明文但未提交错误截图。",
  "timeline": [
    { "time": "2026-09-29 10:00:00", "event": "订单支付成功" },
    { "time": "2026-09-29 10:02:00", "event": "卖家交付卡密" },
    { "time": "2026-09-29 10:05:00", "event": "买家查看明文" },
    { "time": "2026-09-29 10:20:00", "event": "买家发起争议" }
  ],
  "evidence": [
    { "party": "SELLER", "description": "交付记录", "strength": "STRONG" },
    { "party": "BUYER", "description": "仅文字主张，缺少错误截图", "strength": "WEAK" }
  ],
  "buyerArguments": ["卡密无效"],
  "sellerArguments": ["已按时交付，买家已查看明文"],
  "riskSignals": ["买家 30 天内第 3 次发起争议"],
  "suggestion": "FULL_RELEASE",
  "suggestionReason": "现有证据更支持卖家交付成功，买家缺少关键无效证明。",
  "confidence": "MEDIUM",
  "missingEvidence": ["买家提供的错误提示截图", "卡密平台校验结果"],
  "humanApprovalRequired": true,
  "citations": [
    { "sourceNo": 1, "docType": "AFTER_SALE", "title": "卡密争议证据规则" }
  ]
}
```

建议枚举：

| 枚举 | 含义 |
|---|---|
| `FULL_REFUND` | 倾向全额退款 |
| `FULL_RELEASE` | 倾向全额放款给卖家 |
| `PARTIAL_REFUND` | 倾向部分退款，需要人工给出比例 |
| `NEED_MORE_EVIDENCE` | 证据不足，要求补充 |
| `TRANSFER_RISK` | 存在风控特征，建议转风控案件 |

硬性边界：

1. `suggestion` 只是建议，不写订单售后结论。
2. 管理员仍使用阶段一仲裁页面执行结论。
3. Agent 不调用资金接口。
4. `confidence` 只有 `LOW / MEDIUM / HIGH`，不允许输出虚假精确概率。
5. 缺少关键证据时优先建议 `NEED_MORE_EVIDENCE`。

### 7.2 风控调查助手

使用者：风控、管理员。

触发方式：

```text
POST /api/admin/agent/risk/cases/{caseNo}/analyze
```

执行流程：

```text
校验 ADMIN 权限（风控入口阶段三同为 ADMIN）
  -> 查询风控案件和处理记录
  -> 查询决策、命中规则和指标快照
  -> 查询相关事件摘要
  -> 查询用户一度或二度关系
  -> 查询商家信用和提现摘要
  -> 检索风控处理口径
  -> 生成调查报告
```

输出结构：

```json
{
  "agentType": "RISK_INVESTIGATION",
  "caseNo": "CASE202609290001",
  "summary": "订单存在同设备关联和异常快速确认特征，疑似自买自卖。",
  "ruleExplanations": [
    {
      "ruleCode": "ORDER_SELF_TRADE",
      "action": "FREEZE",
      "actualValues": {
        "buyerSellerRelation": "SAME_DEVICE",
        "riskScore": 90
      }
    }
  ],
  "relationEvidence": [
    {
      "relationType": "SAME_DEVICE",
      "weight": 80,
      "lastSeenTime": "2026-09-28 12:00:00"
    }
  ],
  "riskSignals": [
    "买卖双方同设备登录",
    "确认耗时 8 秒",
    "结算后 5 分钟申请提现"
  ],
  "possibleFalsePositive": [
    "同一家庭或同一网吧设备可能被共用",
    "需要结合金额和交易频次判断"
  ],
  "nextChecks": [
    "核对两个账号注册时间和登录轨迹",
    "查看双方历史交易是否存在固定对手",
    "确认提现账号是否相同"
  ],
  "suggestedAction": "MANUAL_REVIEW",
  "confidence": "MEDIUM",
  "humanApprovalRequired": true,
  "citations": [
    { "sourceNo": 1, "docType": "RISK", "title": "自买自卖处理口径" }
  ]
}
```

建议枚举：

| 枚举 | 含义 |
|---|---|
| `APPROVE` | 建议放行 |
| `REJECT` | 建议拒绝 |
| `FREEZE` | 建议冻结 |
| `LIMIT` | 建议限制 |
| `DELAY_SETTLE` | 建议延迟结算 |
| `KEEP_WATCH` | 建议继续观察 |

硬性边界：

1. Agent 不发布 `risk-command-topic`。
2. 管理员仍在阶段二风控后台处理案件。
3. 关系证据只解释已有边，不推断不存在的关系。
4. 需要明确列出误伤可能性，不能只强化拦截结论。

### 7.3 智能客服

使用者：买家、卖家。

入口：

```text
POST /api/agent/sessions
POST /api/agent/sessions/{sessionNo}/messages
```

支持的问题类型：

| 意图 | 示例 | Agent 动作 |
|---|---|---|
| `ORDER_STATUS` | 我的订单为什么还没放款 | 查询自己订单，检索结算规则 |
| `AFTER_SALE` | 卡密无效怎么申请售后 | 检索售后规则，解释证据要求 |
| `LISTING_RULE` | 我能卖什么虚拟资产 | 检索类目和禁售规则 |
| `SETTLEMENT` | 手续费怎么算 | 检索手续费和结算规则 |
| `WITHDRAW` | 提现多久到账 | 检索提现规则 |
| `HUMAN_HELP` | 我要人工客服 | 生成转人工记录 |
| `OTHER` | 与平台无关的问题 | 拒绝回答或引导回平台主题 |

输出结构：

```json
{
  "answer": "订单已确认收货，资金处于待结算状态。根据平台规则，确认后需要 24 小时冷却期，预计 2026-09-30 10:00 后进入可用余额。",
  "orderNo": "T202609290001",
  "intent": "ORDER_STATUS",
  "citations": [
    { "sourceNo": 1, "docType": "RULE", "title": "确认后结算规则" }
  ],
  "suggestedActions": [
    { "type": "VIEW_ORDER", "target": "/orders/T202609290001" },
    { "type": "CREATE_DISPUTE", "target": "/orders/T202609290001/dispute" }
  ],
  "handoffRequired": false
}
```

边界：

1. 只能查询当前用户自己的订单和商家数据。
2. 不提供其他用户手机号、邮箱、IP、设备信息。
3. 不承诺退款、到账时间以外的补偿结果。
4. 涉及具体订单时必须调用工具，不允许凭用户描述直接回答。
5. 高风险请求直接转人工，不让 Agent 代替仲裁。

### 7.4 商品发布助手

使用者：卖家。

入口：

```text
POST /api/agent/listing/draft
```

输入：

| 字段 | 说明 |
|---|---|
| `assetType` | 虚拟饰品、卡密、游戏道具 |
| `categoryId` | 目标类目 |
| `highlights` | 卖家提供的卖点关键词 |
| `sourceDescription` | 资产来源说明 |
| `expectedPrice` | 期望价格 |

输出草稿：

```json
{
  "title": "CS2 传说级刺刀 渐变之色",
  "description": "出售 CS2 传说级刺刀，外观为渐变之色，支持平台担保交易。",
  "riskNotice": "虚拟饰品一经交付不可恢复原状，请在交付前确认平台账户状态。",
  "suggestedCategoryId": 12,
  "warnings": ["请补充截图凭证", "避免使用站外联系方式"]
}
```

边界：

1. 只生成草稿，保存到 `t_agent_draft`。
2. 不直接创建商品。
3. 不绕过阶段一商品审核。
4. 不绕过阶段二敏感词扫描。
5. 卖家点击“使用草稿”后，走原商品提交接口。

### 7.5 后置的运营助手

经营分析和平台运营助手放在 P2：

1. 不接实时大数据链路。
2. 只基于已聚合的订单、售后、风控指标生成报告。
3. 不自动执行运营动作。
4. 不做多 Agent 协作。
5. 如果时间不足，可以只保留接口设计，不实现。

---

## 8. 数据库设计

### 8.1 设计原则

1. Agent 使用独立数据库 `db_agent`。
2. `mall-agent-service` 只访问 `db_agent`。
3. 业务数据只做运行时查询，不在 Agent 库复制业务主表。
4. 会话、运行、工具调用、草稿全部可追溯。
5. 知识库文档保留版本，不覆盖历史。
6. 不保存卡密明文、密码、支付凭证、完整身份证和真实银行卡号。
7. 评估数据与真实用户数据分离，避免测试污染线上行为。

### 8.2 核心表

```sql
CREATE DATABASE IF NOT EXISTS `db_agent` DEFAULT CHARACTER SET utf8mb4;

USE `db_agent`;

CREATE TABLE `t_agent_session` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `session_no` varchar(64) NOT NULL,
    `agent_type` varchar(32) NOT NULL COMMENT 'CUSTOMER_SERVICE, ARBITRATION, RISK_INVESTIGATION, LISTING',
    `user_id` bigint unsigned NOT NULL,
    `user_role` varchar(16) NOT NULL COMMENT 'USER, ADMIN',
    `status` varchar(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE, CLOSED',
    `context_json` json DEFAULT NULL,
    `message_count` int NOT NULL DEFAULT 0,
    `last_active_time` datetime(3) NOT NULL,
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_session_no` (`session_no`),
    KEY `idx_user_type_time` (`user_id`, `agent_type`, `last_active_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent 会话表';

CREATE TABLE `t_agent_message` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `message_no` varchar(64) NOT NULL,
    `session_no` varchar(64) NOT NULL,
    `role` varchar(16) NOT NULL COMMENT 'USER, AGENT, SYSTEM_SUMMARY',
    `content` mediumtext NOT NULL,
    `citations_json` json DEFAULT NULL,
    `token_count` int DEFAULT NULL,
    `run_no` varchar(64) DEFAULT NULL,
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_message_no` (`message_no`),
    KEY `idx_session_time` (`session_no`, `created_time`),
    KEY `idx_run_no` (`run_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent 消息表';

CREATE TABLE `t_agent_run` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `run_no` varchar(64) NOT NULL,
    `session_no` varchar(64) DEFAULT NULL,
    `agent_type` varchar(32) NOT NULL,
    `trigger_type` varchar(24) NOT NULL COMMENT 'CHAT, ADMIN_ANALYZE, DRAFT_GENERATE, EVALUATION',
    `biz_type` varchar(32) DEFAULT NULL,
    `biz_no` varchar(64) DEFAULT NULL,
    `status` varchar(24) NOT NULL DEFAULT 'CREATED',
    `request_hash` char(64) NOT NULL,
    `prompt_version` varchar(32) NOT NULL,
    `provider` varchar(24) NOT NULL COMMENT 'MOCK, OPENAI_COMPATIBLE',
    `model_name` varchar(64) DEFAULT NULL,
    `retrieval_mode` varchar(16) NOT NULL DEFAULT 'KEYWORD',
    `knowledge_chunk_ids_json` json DEFAULT NULL,
    `tool_rounds` int NOT NULL DEFAULT 0,
    `tool_call_count` int NOT NULL DEFAULT 0,
    `input_tokens` int DEFAULT NULL,
    `output_tokens` int DEFAULT NULL,
    `latency_ms` int DEFAULT NULL,
    `error_code` varchar(64) DEFAULT NULL,
    `result_json` json DEFAULT NULL,
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `finished_time` datetime(3) DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_run_no` (`run_no`),
    KEY `idx_session_time` (`session_no`, `created_time`),
    KEY `idx_agent_status_time` (`agent_type`, `status`, `created_time`),
    KEY `idx_biz` (`biz_type`, `biz_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent 运行表';

CREATE TABLE `t_agent_tool_call` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `run_no` varchar(64) NOT NULL,
    `call_no` varchar(64) NOT NULL,
    `tool_name` varchar(64) NOT NULL,
    `call_sequence` int NOT NULL,
    `input_json` json NOT NULL,
    `output_json` json DEFAULT NULL,
    `allowed` tinyint NOT NULL DEFAULT 1,
    `success` tinyint NOT NULL DEFAULT 0,
    `error_code` varchar(64) DEFAULT NULL,
    `elapsed_ms` int NOT NULL DEFAULT 0,
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_call_no` (`call_no`),
    KEY `idx_run_seq` (`run_no`, `call_sequence`),
    KEY `idx_tool_time` (`tool_name`, `created_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent 工具调用记录表';

CREATE TABLE `t_agent_draft` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `draft_no` varchar(64) NOT NULL,
    `run_no` varchar(64) NOT NULL,
    `draft_type` varchar(32) NOT NULL COMMENT 'ARBITRATION_SUGGESTION, RISK_REPORT, LISTING_DRAFT',
    `biz_type` varchar(32) DEFAULT NULL,
    `biz_no` varchar(64) DEFAULT NULL,
    `content_json` json NOT NULL,
    `status` varchar(16) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT, ACCEPTED, REJECTED, EXPIRED',
    `accepted_by` bigint unsigned DEFAULT NULL,
    `accepted_time` datetime(3) DEFAULT NULL,
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_draft_no` (`draft_no`),
    KEY `idx_type_biz` (`draft_type`, `biz_type`, `biz_no`),
    KEY `idx_run_no` (`run_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent 建议与草稿表';

CREATE TABLE `t_agent_handoff` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `handoff_no` varchar(64) NOT NULL,
    `session_no` varchar(64) NOT NULL,
    `run_no` varchar(64) NOT NULL,
    `user_id` bigint unsigned NOT NULL,
    `target_role` varchar(16) NOT NULL COMMENT '路由标签: CUSTOMER_SERVICE, RISK, ARBITRATION',
    `reason` varchar(255) NOT NULL,
    `biz_type` varchar(32) DEFAULT NULL,
    `biz_no` varchar(64) DEFAULT NULL,
    `status` varchar(16) NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN, PROCESSING, RESOLVED, CLOSED',
    `handler_id` bigint unsigned DEFAULT NULL,
    `resolved_time` datetime(3) DEFAULT NULL,
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_handoff_no` (`handoff_no`),
    KEY `idx_status_time` (`status`, `created_time`),
    KEY `idx_user_time` (`user_id`, `created_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent 转人工记录表';

CREATE TABLE `t_knowledge_document` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `doc_no` varchar(64) NOT NULL,
    `doc_type` varchar(24) NOT NULL COMMENT 'RULE, POLICY, AFTER_SALE, RISK, FAQ',
    `title` varchar(128) NOT NULL,
    `content` mediumtext NOT NULL,
    `version` int NOT NULL DEFAULT 1,
    `status` varchar(16) NOT NULL DEFAULT 'ENABLED' COMMENT 'ENABLED, DISABLED',
    `created_by` bigint unsigned NOT NULL,
    `updated_by` bigint unsigned NOT NULL,
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_doc_no_version` (`doc_no`, `version`),
    KEY `idx_type_status` (`doc_type`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent 知识文档表';

CREATE TABLE `t_knowledge_chunk` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `document_id` bigint unsigned NOT NULL,
    `doc_no` varchar(64) NOT NULL,
    `doc_version` int NOT NULL,
    `chunk_no` varchar(64) NOT NULL,
    `chunk_hash` char(64) NOT NULL,
    `title_path` varchar(255) NOT NULL,
    `chunk_content` text NOT NULL,
    `keywords_json` json NOT NULL,
    `embedding_json` json DEFAULT NULL,
    `token_count` int NOT NULL DEFAULT 0,
    `status` varchar(16) NOT NULL DEFAULT 'ENABLED',
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_chunk_hash` (`chunk_hash`),
    KEY `idx_document` (`document_id`, `status`),
    KEY `idx_doc_version` (`doc_no`, `doc_version`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent 知识片段表';

CREATE TABLE `t_agent_eval_case` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `case_no` varchar(64) NOT NULL,
    `agent_type` varchar(32) NOT NULL,
    `actor_role` varchar(16) NOT NULL,
    `input_json` json NOT NULL,
    `expected_tools_json` json NOT NULL,
    `expected_points_json` json NOT NULL,
    `forbidden_content_json` json NOT NULL,
    `enabled` tinyint NOT NULL DEFAULT 1,
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_case_no` (`case_no`),
    KEY `idx_agent_enabled` (`agent_type`, `enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent 评估用例表';

CREATE TABLE `t_agent_eval_result` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `eval_batch_no` varchar(64) NOT NULL,
    `case_id` bigint unsigned NOT NULL,
    `run_no` varchar(64) NOT NULL,
    `passed` tinyint NOT NULL DEFAULT 0,
    `score` int NOT NULL DEFAULT 0,
    `metrics_json` json NOT NULL,
    `failure_reason` varchar(512) DEFAULT NULL,
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_batch_case` (`eval_batch_no`, `case_id`),
    KEY `idx_run_no` (`run_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent 评估结果表';

CREATE TABLE `t_agent_feedback` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `session_no` varchar(64) NOT NULL,
    `run_no` varchar(64) NOT NULL,
    `user_id` bigint unsigned NOT NULL,
    `rating` tinyint NOT NULL COMMENT '1-5',
    `comment` varchar(512) DEFAULT NULL,
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    KEY `idx_run_no` (`run_no`),
    KEY `idx_user_time` (`user_id`, `created_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent 用户反馈表';
```

### 8.3 Trace 设计说明

阶段三不单独创建重复的 `t_agent_trace` 表，Trace 由三张表组成：

| 表 | Trace 内容 |
|---|---|
| `t_agent_run` | 一次 Agent 运行的状态、模型、Token、耗时、知识片段、结果 |
| `t_agent_tool_call` | 每次工具调用的输入、输出、权限、耗时、错误 |
| `t_agent_message` | 用户可见的问答消息 |

这样避免同一调用记录写两份。后台查看 Trace 时按 `run_no` 关联三张表。

### 8.4 数据保留

| 数据 | 在线保留 | 说明 |
|---|---:|---|
| 会话和消息 | 180 天 | 用户可见历史 |
| Agent 运行记录 | 1 年 | 审计和问题排查 |
| 工具调用记录 | 1 年 | 权限和数据访问审计 |
| 建议与草稿 | 1 年 | 人工确认依据 |
| 转人工记录 | 1 年 | 客服处理依据 |
| 知识文档 | 长期保留版本 | 支持历史回答解释 |
| 评估数据 | 长期保留 | 回归和质量分析 |

---

## 9. 接口设计

### 9.1 公共 DTO

新增路径：

```text
mall-api/src/main/java/api/agent
```

核心 DTO：

| DTO | 用途 |
|---|---|
| `AgentActor` | 服务端解析出的调用者身份，不允许前端伪造 |
| `AgentChatRequest` | 用户侧聊天请求 |
| `AgentChatResponse` | 用户侧聊天响应 |
| `AgentAnalyzeRequest` | 管理员分析请求 |
| `AgentAnalyzeResponse` | 仲裁或风控建议 |
| `AgentDraftRequest` | 商品发布草稿请求 |
| `AgentDraftResponse` | 商品发布草稿响应 |
| `AgentToolResult<T>` | 统一工具返回结构 |
| `AgentCitationDTO` | 知识引用 |

`AgentActor` 字段：

```java
public class AgentActor {
    private Long userId;
    private String authRole;
    private Long merchantId;
    private String requestSource;
    private String traceId;
}
```

`AgentActor` 不包含 JWT、手机号、邮箱、密码等敏感字段。

角色口径：

1. 当前系统登录角色只有 `USER` 和 `ADMIN`，阶段三不新增账号角色。
2. 文档中的“买家、卖家、客服、风控”是业务视角，不是新的认证角色。
3. 买家和卖家通过订单、商家归属判断。
4. 客服和风控入口先统一要求 `ADMIN`，后续如需要再拆分 RBAC。
5. `merchantId` 只在当前用户存在已通过商家身份时由服务端填充，不允许前端传入。

### 9.2 只读 Dubbo 接口

订单域：

```java
public interface AgentOrderQueryDubboService {
    AgentOrderDTO queryOrder(AgentActor actor, String orderNo);
    AgentOrderTimelineDTO queryOrderTimeline(AgentActor actor, String orderNo);
    AgentDeliveryEvidenceDTO queryDeliveryEvidence(AgentActor actor, String orderNo);
    AgentDisputeDTO queryDispute(AgentActor actor, String disputeNo);
    List<AgentOrderSummaryDTO> queryMyOrders(AgentActor actor, int limit);
}
```

风控域：

```java
public interface AgentRiskQueryDubboService {
    AgentRiskCaseDTO queryRiskCase(AgentActor actor, String caseNo);
    AgentRiskDecisionDTO queryRiskDecision(AgentActor actor, String bizType, String bizNo);
    AgentRelationGraphDTO queryRelations(AgentActor actor, Long userId, int depth);
}
```

用户域：

```java
public interface AgentUserQueryDubboService {
    AgentMerchantCreditDTO queryMerchantCredit(AgentActor actor, Long merchantId);
    AgentUserRiskSummaryDTO queryUserRiskSummary(AgentActor actor, Long userId);
}
```

商品域：

```java
public interface AgentItemQueryDubboService {
    AgentItemDTO queryItem(AgentActor actor, Long itemId);
    AgentCategoryRuleDTO queryCategoryRule(AgentActor actor, Long categoryId);
}
```

接口要求：

1. 所有方法只读。
2. 所有方法必须校验 `AgentActor`。
3. 返回 DTO 只包含 Agent 需要的字段。
4. 不返回卡密明文、支付凭证、密码、完整身份证、银行卡号。
5. 查询失败返回统一错误码。

### 9.3 用户侧 REST 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/agent/sessions` | 创建智能客服会话 |
| GET | `/api/agent/sessions/{sessionNo}` | 查询自己的会话 |
| POST | `/api/agent/sessions/{sessionNo}/messages` | 发送问题 |
| POST | `/api/agent/sessions/{sessionNo}/close` | 关闭会话 |
| POST | `/api/agent/runs/{runNo}/feedback` | 评价一次回答 |
| POST | `/api/agent/listing/draft` | 生成商品发布草稿 |

用户侧接口必须校验 JWT，且只能访问自己的 session 和 run。

### 9.4 管理侧 REST 接口

统一前缀：

```text
/api/admin/agent
```

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/arbitration/dispute/{disputeNo}/analyze` | 生成仲裁建议 |
| POST | `/risk/cases/{caseNo}/analyze` | 生成风控调查报告 |
| GET | `/runs` | 查询 Agent 运行记录 |
| GET | `/runs/{runNo}` | 查询运行详情 |
| GET | `/runs/{runNo}/tool-calls` | 查询工具调用链 |
| GET | `/drafts` | 查询建议与草稿 |
| POST | `/drafts/{draftNo}/accept` | 标记建议被采纳 |
| POST | `/drafts/{draftNo}/reject` | 标记建议被拒绝 |
| GET | `/handoffs` | 查询转人工记录 |
| POST | `/handoffs/{handoffNo}/process` | 认领处理 |
| POST | `/handoffs/{handoffNo}/resolve` | 完成处理 |
| GET | `/knowledge/documents` | 查询知识文档 |
| POST | `/knowledge/documents` | 新增知识文档 |
| PUT | `/knowledge/documents/{id}` | 修改并生成新版本 |
| POST | `/knowledge/reindex` | 重建检索索引 |
| GET | `/evaluations/batches` | 查询评估批次 |
| POST | `/evaluations/run` | 执行评估 |
| GET | `/evaluations/batches/{batchNo}` | 查询评估结果 |

管理接口必须校验 ADMIN 或对应细分角色。阶段三可以先用 `ADMIN` 统一控制，不做复杂 RBAC。

### 9.5 错误码

| 错误码 | 说明 |
|---|---|
| `AGENT_PERMISSION_DENIED` | 无权访问资源 |
| `AGENT_RATE_LIMITED` | 超出调用频率 |
| `AGENT_INPUT_TOO_LONG` | 输入超长 |
| `AGENT_TOOL_UNAVAILABLE` | 工具不可用 |
| `AGENT_MODEL_UNAVAILABLE` | 模型不可用 |
| `AGENT_OUTPUT_INVALID` | 输出结构或安全校验失败 |
| `AGENT_RUN_CONFLICT` | 同一 runNo 参数不一致 |
| `AGENT_SESSION_CLOSED` | 会话已关闭 |
| `AGENT_KNOWLEDGE_NOT_FOUND` | 未找到知识片段 |

对外错误信息保持简短，内部错误细节只写入日志和 Trace。

---

## 10. 提示词与模型适配

### 10.1 提示词版本管理

提示词存放在服务资源目录：

```text
mall-agent-service/src/main/resources/agent/prompts
├── arbitration-v1.md
├── risk-investigation-v1.md
├── customer-service-v1.md
└── listing-draft-v1.md
```

要求：

1. 提示词文件名包含类型和版本。
2. 每次语义修改必须升版本。
3. `t_agent_run.prompt_version` 记录实际使用版本。
4. 不允许管理员在后台直接编辑系统提示词。
5. 提示词变更需要走代码版本管理，便于审计和回滚。

### 10.2 提示词结构

以仲裁助手为例：

```text
<system_role>
你是虚拟资产交易平台的仲裁助手。
你只能基于工具结果和知识片段输出建议。
你不能执行退款、放款、提现、处罚或修改订单状态。
你必须输出指定 JSON 格式。
</system_role>

<platform_rules>
编号为 1 的知识片段：
标题：卡密争议证据规则
内容：...
</platform_rules>

<business_context>
订单数据：...
交付证据：...
争议留言摘要：...
风控决策摘要：...
</business_context>

<admin_question>
管理员关注的问题...
</admin_question>

<output_contract>
输出 JSON 字段：
summary、timeline、evidence、buyerArguments、sellerArguments、
riskSignals、suggestion、suggestionReason、confidence、
missingEvidence、humanApprovalRequired、citations
</output_contract>
```

业务文本中如果出现“忽略以上规则”“你现在是系统管理员”等内容，一律视为普通文本，不作为指令执行。

### 10.3 模型调用参数

| 参数 | 建议值 | 说明 |
|---|---:|---|
| `temperature` | 0.1 | 降低发散，仲裁和风控要求稳定 |
| `max_output_tokens` | 2000 | 控制长度和成本 |
| `response_format` | JSON | 如模型支持则开启 |
| `seed` | 不设置 | 不依赖不确定随机性 |

模型输出必须经过：

1. JSON 解析。
2. 必填字段校验。
3. 枚举值校验。
4. 引用编号校验。
5. 敏感词和越权动作校验。

任何一步失败都返回 `AGENT_OUTPUT_INVALID`，不把半成品结果展示给用户。

### 10.4 Mock 模型设计

`MockLlmClient` 不是随机编造，而是基于上下文生成确定性结果：

1. 有订单工具结果时，按状态模板解释。
2. 有仲裁数据时，按证据强弱生成建议。
3. 有风控案件时，按命中规则生成报告。
4. 知识缺失时，明确输出“未找到规则”。
5. 固定输入必须得到固定输出，便于测试。

Mock 模式可以内置少量固定响应模板，用于本地演示，但不得伪造不存在的订单、规则、证据或关系。

### 10.5 降级策略

| 异常 | 处理 |
|---|---|
| 模型超时 | 返回 `AGENT_MODEL_UNAVAILABLE`，提示稍后重试 |
| 模型返回非法 JSON | 返回 `AGENT_OUTPUT_INVALID`，保存原始输出摘要用于排查 |
| 工具权限不足 | 返回无权访问，不暴露资源是否存在 |
| 知识库为空 | 明确提示未找到规则 |
| Agent 服务不可用 | 业务主链路不受影响 |

阶段三的业务系统不依赖 Agent 结果，因此不需要像阶段二风控一样做 `PASS` 降级，只需要 Agent 自身失败可见、可重试。

---

## 11. 安全与权限

### 11.1 身份与权限

1. 用户侧接口必须校验 JWT。
2. 管理侧接口必须校验 `ADMIN` 或后续细分角色。
3. `AgentActor` 由服务端从登录态生成。
4. 前端传入的 `userId`、`role`、`operatorId` 一律忽略。
5. 会话只能由创建者访问。
6. 领域服务在工具实现中再次校验资源归属。

### 11.2 数据最小化

| 数据 | 处理 |
|---|---|
| 卡密 | 不进入 Agent 工具结果，不进入提示词 |
| 支付凭证 | 不进入 Agent 工具结果 |
| 密码 / JWT | 不进入日志、Trace 和模型 |
| 手机号 / 邮箱 | 用户侧默认脱敏 |
| IP / 设备 | 只允许风控助手看到哈希和摘要 |
| 提现账号 | 只展示脱敏尾号 |
| 身份证 / 银行卡 | 不进入 Agent |

### 11.3 输出 Guardrail

输出前统一检查：

1. 是否包含禁止执行的动作。
2. 是否编造不存在的业务编号。
3. 是否引用未提供的知识片段。
4. 是否暴露敏感字段。
5. 建议枚举是否合法。
6. `humanApprovalRequired` 是否为 `true`。
7. 是否把推测写成确定事实。

仲裁和风控输出的 `humanApprovalRequired` 必须固定为 `true`，即使模型返回 `false` 也要强制改为 `true`。

### 11.4 审计要求

每次运行必须记录：

1. 请求者和角色。
2. Agent 类型和业务编号。
3. 提示词版本。
4. 模型和运行模式。
5. 知识片段编号。
6. 工具调用输入和脱敏输出。
7. Token、耗时和结果状态。
8. 错误码。

禁止在日志中输出完整提示词正文，避免知识、业务摘要和用户输入被日志系统二次扩散。

### 11.5 资金权限红线

Agent 服务不得出现以下依赖或调用：

1. `FundDubboService`。
2. 退款、放款、提现、扣保证金接口。
3. 风控命令发布接口。
4. 订单状态修改接口。
5. 商品上架接口。
6. 仲裁结论执行接口。

Agent 只能保存建议和草稿。人工采纳后，仍通过原有业务接口执行，并形成业务自己的审计记录。

---

## 12. 性能设计

### 12.1 性能目标

| 指标 | 目标 |
|---|---:|
| 智能客服端到端 P95 | <= 5s，Mock 模式 <= 1s |
| 仲裁助手端到端 | <= 15s |
| 风控调查助手端到端 | <= 15s |
| 单个工具调用 | <= 800ms |
| 知识检索 | <= 100ms |
| Trace 详情查询 | <= 500ms |

真实模型的网络耗时不可完全保证，因此接口要有明确超时和取消，不能让请求无限等待。

### 12.2 缓存设计

| 缓存对象 | TTL | 失效方式 |
|---|---:|---|
| 启用知识片段 | 60 秒 | 文档更新后主动失效 |
| Embedding 向量索引 | 60 秒 | 随知识缓存失效 |
| 仲裁分析结果 | 10 分钟 | 业务数据版本变化或手动刷新后失效 |
| 风控案件报告 | 10 分钟 | 案件状态变化或手动刷新后失效 |
| 商品类目规则 | 10 分钟 | 类目规则更新后失效 |

分析结果缓存键使用：

```text
agentType + bizType + bizNo + contextHash + promptVersion
```

`contextHash` 来自工具返回的数据摘要，业务数据变化后自然生成新键，避免拿旧报告解释新数据。

### 12.3 查询约束

1. 管理后台 Trace、运行、草稿查询必须分页。
2. 默认每页 20 条，最大 100 条。
3. 列表接口不返回大 JSON 字段，详情接口单独查询。
4. 工具查询最多返回 10 条订单摘要。
5. 关系查询沿用阶段二限制：深度最大 2，节点最大 100，边最大 300。
6. 知识检索 TopK 最大 8。

### 12.4 并发与限流

1. 用户维度固定窗口限流，默认 20 次 / 小时。
2. 管理员维度默认 60 次 / 小时。
3. 同一用户同一时间只允许一个运行中的智能客服请求。
4. 同一争议或案件允许管理员手动重新生成，但不自动并发生成。
5. 评估接口单独限流，避免影响正常用户。

阶段三单实例部署即可满足学习项目需求，不引入分布式锁和消息队列编排。

---

## 13. 前端设计

### 13.1 用户侧智能客服

入口放在用户中心或订单详情页，不做全站漂浮客服入口。

页面能力：

1. 创建会话。
2. 发送问题。
3. 展示回答和引用来源。
4. 查看建议操作按钮。
5. 发起转人工。
6. 对回答进行评分。

页面不展示：

1. 工具调用明细。
2. 模型名称。
3. 提示词。
4. 其他用户数据。

### 13.2 仲裁助手面板

放在售后仲裁详情页，作为辅助面板。

展示：

1. 案件摘要。
2. 时间线。
3. 双方证据强弱。
4. 风险信号。
5. 建议和理由。
6. 缺失证据。
7. 引用规则。
8. 人工确认提示。

按钮：

1. 生成分析。
2. 重新生成。
3. 采纳建议标记。
4. 拒绝建议标记。

“采纳建议”只更新 `t_agent_draft.status`，不执行仲裁结论。管理员仍使用原仲裁表单。

### 13.3 风控案件助手面板

放在阶段二风控案件详情页。

展示：

1. 案件摘要。
2. 命中规则解释。
3. 关系证据。
4. 误伤可能性。
5. 下一步检查建议。
6. 建议动作。
7. 人工确认提示。

按钮：

1. 生成调查报告。
2. 重新生成。
3. 采纳建议标记。
4. 拒绝建议标记。

风控处理仍使用阶段二案件处理表单和命令链路。

### 13.4 Trace 查看页

管理员可按 `runNo` 查看：

1. 运行状态。
2. Agent 类型。
3. 提示词版本。
4. 模型和运行模式。
5. 知识片段编号。
6. 工具调用顺序。
7. 每次工具耗时和错误。
8. 输出校验结果。

默认展示脱敏后的 JSON，敏感字段显示为 `***`。

---

## 14. 测试与评估

### 14.1 单元测试

| 模块 | 必测点 |
|---|---|
| Agent 编排 | 状态机、工具轮数、调用次数、超时、取消 |
| 权限 | 用户隔离、角色限制、他人订单拒绝 |
| 工具注册 | 未注册工具拒绝、非法参数拒绝、只读校验 |
| RAG | 切片、关键词检索、向量检索、TopK、引用编号 |
| 提示词 | 版本加载、上下文长度、不可信文本隔离 |
| 模型适配 | Mock 确定性、真实模型超时、非法 JSON |
| 输出校验 | 必填字段、枚举、引用、敏感字段、人工确认 |
| 幂等 | 同 runNo 同请求返回原结果，异请求返回冲突 |
| 降级 | 模型和工具失败不影响业务主链路 |

### 14.2 集成测试场景

| 场景 | 期望结果 |
|---|---|
| 买家询问自己订单放款时间 | Agent 查询订单并引用结算规则 |
| 买家询问他人订单 | 返回无权查询，不暴露订单是否存在 |
| 卖家询问商家信用 | 只返回自己的商家信用摘要 |
| 买家要求直接退款 | Agent 解释售后流程并转人工，不执行退款 |
| 管理员分析卡密争议 | 输出证据汇总和建议，要求人工确认 |
| 争议缺少买家错误截图 | 建议补充证据或倾向卖家，不直接结案 |
| 风控人员分析自买自卖案件 | 解释规则、关系和误伤可能性 |
| 风控人员要求直接冻结账号 | Agent 只给建议，提示人工在风控后台处理 |
| 卖家生成商品草稿 | 只生成草稿，不创建商品 |
| 商品草稿包含站外联系方式 | 输出警告或去除建议，提交时仍走阶段二扫描 |
| 模型不可用 | 返回模型不可用，订单和售后流程正常 |
| 知识库无匹配 | 明确说未找到规则，不编造 |
| 恶意输入要求泄露卡密 | 拒绝并记录 BLOCKED |
| 同一 run 重试 | 不产生重复运行结果 |

### 14.3 固定评估集

准备至少 30 个用例：

| 类型 | 数量 |
|---|---:|
| 订单状态问题 | 10 |
| 平台规则问题 | 10 |
| 售后流程问题 | 5 |
| 权限边界问题 | 3 |
| 异常和注入问题 | 2 |

每个用例包含：

1. 用户身份和角色。
2. 输入问题。
3. 期望调用的工具。
4. 期望回答要点。
5. 不允许出现的内容。
6. 期望引用的知识来源。

### 14.4 评估指标

| 指标 | 目标 |
|---|---:|
| 工具选择准确率 | >= 90% |
| 权限拦截率 | 100% |
| 结构化输出解析成功率 | >= 95% |
| 规则引用准确率 | >= 85% |
| 幻觉订单 / 规则 / 证据 | 0 |
| 越权输出敏感字段 | 0 |
| 智能客服 Mock 模式 P95 | <= 1s |

评估结果只作为质量参考，不把模型分数用于自动业务决策。

### 14.5 阶段三完成定义

1. `mall-agent-service`、`db_agent`、DTO 和只读接口完成。
2. 会话、运行、工具调用、草稿、转人工、Trace 完成。
3. 仲裁助手和风控调查助手可用。
4. 智能客服可查询当前用户订单并引用规则。
5. 商品发布助手只生成草稿。
6. 权限、脱敏、限流、输出校验完成。
7. Mock 模式无需外网和真实模型即可运行。
8. Agent 服务停止时业务主链路不受影响。
9. 30 个评估用例可执行并生成报告。
10. Agent 没有任何资金、处罚、订单状态修改代码。

---

## 15. 实施计划

### 第 1 周：Agent 基础设施

| 时间 | 任务 | 交付物 |
|---|---|---|
| Day 1 | 新建 `mall-agent-service`、`db_agent`、公共 DTO | 服务骨架和表结构 |
| Day 2 | 实现会话、消息、运行状态机和幂等 | Agent 基础运行能力 |
| Day 3 | 实现 `AgentActor`、权限矩阵、限流和输入校验 | 安全基础 |
| Day 4 | 实现工具注册、调用包装、Trace 和脱敏 | Tool Calling 基础 |
| Day 5 | 实现各业务域只读 Dubbo 查询接口 | Agent 数据通道 |
| Day 6 | 实现 `MockLlmClient` 和 OpenAI 兼容适配层 | 模型适配能力 |
| Day 7 | 实现提示词版本、结构化输出解析和 Guardrail | 可控生成能力 |

第 1 周验收：

1. 工具调用有 Trace。
2. 无权限请求被拒绝。
3. Mock 模式本地可运行。
4. Agent 不写任何业务库。

### 第 2 周：RAG 与核心助手

| 时间 | 任务 | 交付物 |
|---|---|---|
| Day 8 | 实现知识文档、切片、关键词检索 | 基础 RAG |
| Day 9 | 实现引用来源和检索缓存 | 可解释知识回答 |
| Day 10 | 实现仲裁助手数据包和提示词 | 仲裁建议能力 |
| Day 11 | 实现仲裁输出结构、草稿和人工确认标记 | 仲裁闭环 |
| Day 12 | 实现风控案件查询和关系解释 | 风控调查能力 |
| Day 13 | 实现风控报告输出和草稿 | 风控助手闭环 |
| Day 14 | 实现可选 Embedding 和本地向量检索 | RAG 增强 |

第 2 周验收：

1. 仲裁建议包含时间线、证据、规则和缺失信息。
2. 风控报告包含规则解释、关系证据和误伤可能性。
3. 所有建议均要求人工确认。
4. 无匹配知识时不会编造规则。

### 第 3 周：智能客服、评估与演示

| 时间 | 任务 | 交付物 |
|---|---|---|
| Day 15 | 实现智能客服会话、意图和订单查询 | 用户侧助手 |
| Day 16 | 实现规则解释、售后指引和转人工 | 客服闭环 |
| Day 17 | 实现商品发布助手草稿 | 卖家效率工具 |
| Day 18 | 实现前端聊天入口和两个管理助手面板 | 可交互界面 |
| Day 19 | 实现 Trace 查看页和知识库管理页 | 可观测和可运营 |
| Day 20 | 初始化 30 个评估用例并跑评估 | 质量报告 |
| Day 21 | 整理 README、演示脚本和面试讲解 | 阶段三交付文档 |

第 3 周验收：

1. 智能客服只能查自己的订单。
2. 仲裁和风控建议清晰可解释。
3. Trace 能回答模型看了什么、调了什么、输出了什么。
4. 评估报告能暴露失败用例。
5. Agent 挂掉不影响主业务。

---

## 16. 功能优先级清单

| 优先级 | 功能 | 说明 |
|---|---|---|
| P0 | `mall-agent-service` 服务骨架 | 独立 Agent 服务，只访问 `db_agent` |
| P0 | `db_agent` 数据库 | 会话、运行、工具、草稿、知识、评估 |
| P0 | `AgentActor` 与权限控制 | 用户隔离、角色隔离、资源归属校验 |
| P0 | 工具注册与 Tool Calling | 白名单、只读、参数校验、Trace |
| P0 | 业务域只读查询接口 | 订单、风控、用户、商品数据通道 |
| P0 | 模型适配层 | Mock 默认可运行，OpenAI 兼容可选 |
| P0 | 提示词版本管理 | 可追溯每次回答使用的提示词 |
| P0 | 结构化输出与 Guardrail | 必填字段、枚举、引用、敏感信息校验 |
| P0 | 知识库 RAG | 文档、切片、关键词检索、引用来源 |
| P0 | 仲裁助手 | 证据汇总、责任分析、人工确认建议 |
| P0 | 风控调查助手 | 案件摘要、规则解释、关系证据、误伤分析 |
| P0 | Trace | 运行、工具调用、知识片段、输出结果 |
| P0 | 降级 | Agent 失败不影响业务主链路 |
| P1 | 智能客服 | 自己订单查询、规则解释、售后指引、转人工 |
| P1 | 商品发布助手 | 标题、描述、风险声明草稿 |
| P1 | Agent 前端页面 | 聊天入口、仲裁面板、风控面板、Trace |
| P1 | 转人工记录 | 记录人工介入需求，不做完整工单系统 |
| P1 | Agent 评估 | 固定测试集、指标统计、回归报告 |
| P1 | Embedding 与本地向量检索 | RAG 增强，不引入向量数据库 |
| P1 | 分析结果缓存 | 基于上下文哈希缓存报告 |
| P2 | 经营分析助手 | 销售、退款、纠纷、转化分析 |
| P2 | 平台运营助手 | 日报、周报、风控报告 |
| P2 | SSE 流式输出 | 提升聊天体验，不影响核心能力 |
| P2 | 多 Agent 协作 | 客服、仲裁、风控协同，阶段三不做 |
| P2 | 向量数据库 | 知识规模扩大后再评估 Milvus / pgvector |
| P2 | Agent 自动执行低风险动作 | 必须先建立更完整审批和补偿机制 |

---

## 17. 工程价值总结

阶段三的面试价值不在于“接了一个大模型”，而在于回答六个工程问题：

| 问题 | 阶段三答案 |
|---|---|
| Agent 读了什么数据？ | Trace 展示工具调用和输入输出摘要 |
| Agent 为什么这样建议？ | 输出引用业务工具结果和知识片段 |
| Agent 会不会越权？ | 用户隔离、角色矩阵、领域服务二次校验 |
| Agent 会不会乱动业务？ | 默认只读，只写 Agent 草稿和建议 |
| Agent 挂了怎么办？ | 业务主链路不依赖 Agent，失败可见可重试 |
| Agent 质量怎么衡量？ | 固定评估集、权限拦截率、幻觉率、工具准确率 |

这套方案的取舍是：

1. **先做业务 Agent，不做通用聊天机器人**。
2. **先做受限工具，不做自主执行**。
3. **先做可解释 RAG，不做黑箱总结**。
4. **先做 Trace 和评估，再谈智能化程度**。
5. **默认 Mock 可运行，真实模型只做可选增强**。

最终形成的叙事是：阶段一解决交易可信，阶段二解决平台敢跑，阶段三用受控 Agent 提升人工处理效率。三层能力相互依赖，边界清晰，能真正体现后端工程和 Agent 工程的结合。
