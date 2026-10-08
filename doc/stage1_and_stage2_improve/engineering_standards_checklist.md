# 第四批工程规范：CI 检查与 Code Review 清单

| 属性 | 内容 |
| --- | --- |
| 版本 | v1.0 |
| 日期 | 2026-10-08 |
| 对应批次 | 第四批：工程规范（`stage1_stage2_batch4_engineering_standards_dev_design.md`） |
| 适用模块 | `mall-api`、`mall-order-service`、`mall-item-service`、`mall-user-service`、`mall-risk-service`、`mall-gateway-service`、`mall-frontend` |

本文件把设计文档第 9 章的规范固化为可执行清单，作为提交门禁和 CR 依据。

## 1. Java 规范

| 项 | 要求 |
| --- | --- |
| 命名 | 类名名词、方法名动词、包名小写单数 |
| 依赖注入 | 构造器注入，禁止字段注入 |
| 类长度 | 目标不超过 500 行 |
| 方法长度 | 目标不超过 60 行 |
| 构造器依赖 | 目标不超过 7 个，超过时拆分 |
| 事务 | 方法名或注释明确事务边界 |
| 集合 | 优先 `List.of`、`Map.of` 不可变集合 |
| 时间 | 业务时间来自注入的 `Clock`，使用 `LocalDateTime.now(clock)` |
| 金额 | 使用 `BigDecimal` |
| 异常 | 不吞异常，不返回堆栈，业务异常继承 `BizException` 并携带稳定错误码 |
| SQL | 状态和动态条件参数化 |

## 2. 前端规范

1. API 请求集中在 `src/api`，组件不直接 `fetch`。
2. 组件目标不超过 300 行，超过时拆子组件或 Hook。
3. 异步状态显式建模，不用布尔值混用加载和错误。
4. 列表必须有 loading、empty、error 三态。
5. 危险操作必须有确认和禁用状态。
6. 文案集中管理，状态文案来自字典接口。
7. 用户输入先本地校验，再提交后端。

## 3. 提交与分支规范

分支命名：

```text
codex/{batch}/{topic}
feature/{module}/{topic}
fix/{module}/{topic}
```

提交信息：

```text
type(scope): subject

[optional body]
```

示例：

```text
feat(order): split payment callback service
refactor(risk): introduce command status enum
fix(gateway): sanitize request id
test(trade): add callback replay tests
```

## 4. CI 检查

最小流水线：

```text
1. git diff --check
2. mvn -o -pl mall-api,mall-order-service,mall-risk-service -am test
3. npm --prefix mall-frontend run build
4. npx --prefix mall-frontend oxlint mall-frontend/src
5. bash scripts/ci/engineering-standards-scan.sh --strict
6. 敏感信息扫描（密钥、卡密、手机号、完整回调报文）
7. 生产测试接口扫描（mock / audit 演示接口必须带 @Profile）
```

扫描规则（由 `scripts/ci/engineering-standards-scan.sh` 实现）：

| 规则 | 禁止/要求 |
| --- | --- |
| 状态字面量 | 新代码新增 `"WAIT_PAY"` 等散落字符串，统一使用 `api.enums` |
| Controller 输入 | 新 Controller 方法使用 `@RequestBody Map`，改用 DTO + `@Valid` |
| 注入方式 | 字段注入 `@Autowired` |
| 异常处理 | Controller `catch (Exception e)` 后包装响应 |
| 时间 | Service 新增 `LocalDateTime.now()`，改用 `LocalDateTime.now(clock)` |
| 输出 | `System.out.println`、`printStackTrace` |
| 测试接口 | 生产源码出现未加 `@Profile` 的 mock/test controller |
| 敏感信息 | 响应体出现 `debugCode` |

门禁策略：迁移期默认 warn（`bash scripts/ci/engineering-standards-scan.sh`），
存量清理完成后切换 `--strict` 强制失败。

## 5. Code Review 检查清单

后端：

1. 是否有明确需求和验收标准？
2. 状态流转是否使用枚举和矩阵（`api.enums.*.canTransitionTo`）？
3. 事务内是否有远程调用？
4. 远程操作是否有幂等键和补偿？
5. 参数是否有 DTO 和 Bean Validation？
6. 错误码是否稳定，HTTP 语义是否正确（`ErrorCodes` + `BizException.httpStatus`）？
7. 日志是否包含 traceId 和业务单号，是否脱敏？
8. 单测是否覆盖边界、并发和失败路径？

前端：

1. 是否处理 loading、empty、error、retry？
2. 请求是否可取消，是否防止旧响应覆盖新状态？
3. 操作是否有并发禁用？
4. 危险操作是否有确认？
5. 状态文案是否来自字典？
6. 移动端和键盘可访问性是否可用？

## 6. 本批未落地的后续项

以下项属于设计文档任务表中的后续小版本，本批不强制门禁：

1. `TradeOrderService` 拆分（E-07~E-12）仍按门面保留策略推进。
2. `mall-item-service`、`mall-user-service` 的 `@RequestBody Map` 入参在各自领域服务
   完成 DTO 化后逐 Controller 替换（E-06 分阶段）。
3. `recent-logs` 审计查询改为受权限控制的正式接口（E-13 后续）。
4. ArchUnit 架构测试在引入 `archunit-junit5` 依赖后补充，当前以脚本扫描替代。
5. `@Value` → `@ConfigurationProperties` 逐模块推进，本批仅 `trade.order`（交易订单）落地，
   其余服务（item/user/risk/audit/xxl-job）按同一模板迁移。
6. `LocalDateTime.now()` → `Clock` 注入逐服务推进，本批在 `TradeOrderService` 落地，
   其余服务保持旧实现，由扫描规则持续告警。
7. 网关对 `POST /api/payment/{paymentNo}/mock-pay` 的生产阻断当前依赖
   「服务端 Bean 不创建 + 网关 JWT 鉴权」两道防线；上线前建议在网关显式返回 404。
