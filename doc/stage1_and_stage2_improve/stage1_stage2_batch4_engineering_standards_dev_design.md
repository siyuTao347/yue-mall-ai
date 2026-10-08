# 第四批开发设计：工程规范

| 属性 | 内容 |
| --- | --- |
| 版本 | v1.0 |
| 日期 | 2026-09-30 |
| 状态 | 待评审 |
| 对应批次 | 第四批：工程规范 |
| 涉及模块 | `mall-api`、`mall-order-service`、`mall-item-service`、`mall-user-service`、`mall-risk-service`、`mall-gateway-service`、`mall-frontend` |
| 前置文档 | `stage1_stage2_optimization_review.md`、第一批至第三批开发设计文档 |

本文是开发设计文档，只描述目标方案、代码结构、迁移步骤、任务拆分和验收标准，不直接修改业务代码。

## 1. 背景与目标

当前工程已经完成网关拆分、担保交易、风控体系和基础审计，但可维护性仍有明显短板：

1. 状态值大量使用字符串字面量，分散在服务、Mapper SQL 和前端展示中。
2. `TradeOrderService` 同时承担订单创建、支付回调、交付、确认、结算、售后、仲裁、评价、查询和定时任务处理，职责过多。
3. Controller 大量接收 `Map<String, Object>`，参数转换和校验散落在业务代码中。
4. 异常普遍被 `catch (Exception e)` 后包装成响应体，缺少统一异常体系、稳定错误码和 HTTP 语义。
5. 日志格式不统一，traceId 主要在网关和审计切面中存在，业务服务没有全链路贯通。
6. `@Value` 和 `LocalDateTime.now()` 散落在业务服务中，配置和时间的可测试性不足。
7. `MockPaymentController`、`AuditLogTestController` 等测试能力暴露在生产代码中，服务直连场景存在风险。

本批目标：

- 建立统一状态枚举、DTO、Bean Validation、异常、错误码、响应体和 traceId 规范。
- 将 `TradeOrderService` 拆分为职责清晰的服务，同时保持 Controller API 兼容。
- 将配置从散落 `@Value` 迁移到可校验的 `@ConfigurationProperties`。
- 业务时间统一通过 `Clock` 注入，提升并发、过期和重试场景的可测试性。
- 清理测试接口的生产暴露风险，Mock 与演示能力只在开发环境可用。
- 建立大厂风格的代码规范、CR 检查清单和 CI 检查，保证后续迭代可持续。

非目标：

- 不在本批重写数据库表结构，状态字段继续使用 `varchar` 存储枚举代码，保持兼容。
- 不一次性拆分微服务边界，只在 `mall-order-service` 内部先拆服务职责。
- 不引入新的重型框架，优先使用 Spring Boot、Bean Validation、SLF4J、MDC 和现有网关能力。
- 不改变前四批已定的业务语义和安全方案。

## 2. 总体治理架构

```text
mall-api
    +-- response       ApiResponse、PageResult、错误码契约
    +-- enums          跨服务状态枚举
    +-- context        用户上下文、trace 上下文契约
    +-- exception      业务异常基类和通用异常

mall-order-service
    +-- controller     只做协议适配、权限入口和 @Valid
    +-- service        按订单、支付、交付、售后、结算、查询拆分
    +-- orchestration  跨服务编排，承接第一批 Saga 任务
    +-- infrastructure 远程客户端、时间、配置适配

all services
    +-- config         @ConfigurationProperties + 校验
    +-- web            TraceIdFilter、GlobalExceptionHandler
    +-- logging        结构化日志规范
```

治理原则：

1. **协议稳定**：接口路径和响应外壳保持兼容，内部结构和错误码逐步增强。
2. **小步迁移**：每类治理单独提交、单独测试、单独回滚。
3. **编译期约束**：能用枚举、DTO、Bean Validation 和 ArchUnit 解决的问题，不依赖口头规范。
4. **边界清晰**：Controller 不写业务，Service 不拼协议，Mapper 不写复杂业务语义。
5. **可测试优先**：时间、配置、远程依赖全部可注入，测试不依赖系统当前时间和真实网络。

## 3. 状态枚举治理

### 3.1 现状问题

当前代码中存在大量字符串状态：

```java
"WAIT_PAY"
"PAID"
"DELIVERED"
"CONFIRMED"
"SETTLING"
"SETTLED"
"FROZEN"
"MANUAL_REVIEW"
"PENDING_SEND"
"COMMAND_FAILED"
```

问题包括：

1. 拼写错误只能运行时发现。
2. 状态流转规则分散在多个方法中。
3. 新增状态时无法通过 `switch` 编译检查发现遗漏。
4. 中文文案散落在前端，同一状态可能展示不一致。
5. Mapper SQL 中继续硬编码，容易和服务层状态不同步。

### 3.2 枚举设计

在 `mall-api` 中建立跨服务枚举：

```text
mall-api/src/main/java/api/enums/
    OrderStatus.java
    PayStatus.java
    DeliveryStatus.java
    EscrowStatus.java
    DisputeStatus.java
    MerchantAuditStatus.java
    WithdrawStatus.java
    RiskLevel.java
    RiskStatus.java
    RiskCaseStatus.java
    RiskCommandStatus.java
    RiskScene.java
```

枚举模板：

```java
public enum OrderStatus {
    WAIT_PAY("WAIT_PAY"),
    PAID("PAID"),
    DELIVERED("DELIVERED"),
    CONFIRMED("CONFIRMED"),
    SETTLING("SETTLING"),
    SETTLED("SETTLED"),
    CANCELLED("CANCELLED"),
    REFUNDED("REFUNDED");

    private final String code;

    OrderStatus(String code) {
        this.code = code;
    }

    @JsonValue
    public String getCode() {
        return code;
    }

    @JsonCreator
    public static OrderStatus fromCode(String code) {
        return Arrays.stream(values())
                .filter(value -> value.code.equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Invalid order status: " + code));
    }
}
```

要求：

1. 数据库继续保存 code，不迁移为数字或新字符串。
2. 枚举增加 `fromCodeOrNull`，用于兼容历史脏数据和外部输入校验。
3. 涉及状态流转的枚举增加 `canTransitionTo(OrderStatus target)`。
4. 实体字段可先保持 `String`，Service 层必须使用枚举校验后再赋值；后续分批改为枚举字段。
5. Mapper SQL 中的状态改为 `@Param("fromStatus")`、`@Param("toStatus")`，由 Java 枚举传入。

### 3.3 状态流转矩阵

每个状态枚举必须有显式流转矩阵，并由单元测试覆盖。

订单主状态：

```text
WAIT_PAY -> PAID
WAIT_PAY -> CANCELLED
PAID -> DELIVERED
PAID -> REFUNDED
DELIVERED -> CONFIRMED
DELIVERED -> REFUNDED
CONFIRMED -> SETTLING
CONFIRMED -> REFUNDED
SETTLING -> SETTLED
SETTLING -> REFUNDED
SETTLED -> 终态
CANCELLED / REFUNDED -> 终态
```

支付状态：

```text
INIT -> PAYING
PAYING -> SUCCESS
PAYING -> FAILED
PAYING -> TIMEOUT
SUCCESS / FAILED / TIMEOUT -> 终态
```

托管资金状态：

```text
NONE -> FROZEN
FROZEN -> SETTLE_PENDING
FROZEN -> REFUND_PENDING
SETTLE_PENDING -> SETTLED
REFUND_PENDING -> REFUNDED
SETTLED / REFUNDED -> 终态
```

风控案件状态：

```text
OPEN -> PROCESSING
PROCESSING -> RESOLVED
RESOLVED -> CLOSED
CLOSED -> OPEN
```

### 3.4 迁移步骤

| 步骤 | 内容 | 验证 |
| --- | --- | --- |
| S1 | 新增枚举和状态流转矩阵 | 枚举单元测试 |
| S2 | 新代码禁止新增状态字面量 | ArchUnit / 文本扫描 |
| S3 | 逐服务替换 Service 层字面量 | 服务单元测试 |
| S4 | Mapper SQL 参数化状态 | SQL Mapper 测试 |
| S5 | 前端通过字典接口获取文案 | E2E 状态展示测试 |

兼容要求：

1. 对外 JSON 仍输出原字符串 code。
2. 历史数据中未知 code 不导致接口 500，查询时可返回 `UNKNOWN` 或原始 code，并记录 warn 日志。
3. 状态变更前必须先读当前状态并做流转校验。
4. 不允许在前端自行推断后端状态，只根据后端字典和返回 code 展示。

## 4. 配置类治理

### 4.1 统一规范

用 `@ConfigurationProperties` 替代业务类中的散落 `@Value`：

```text
mall-order-service/src/main/java/com/example/item/config/
    TradeOrderProperties.java
    PaymentCallbackProperties.java
    AuditProperties.java
    XxlJobProperties.java

mall-risk-service/src/main/java/com/example/risk/config/
    RiskMaintenanceProperties.java
    RiskCommandProperties.java
```

配置类示例：

```java
@Validated
@ConfigurationProperties(prefix = "trade.order")
public record TradeOrderProperties(
        @DecimalMin("0") @DecimalMax("100") BigDecimal feeRatePercent,
        @DecimalMin("0.01") BigDecimal minFee,
        @Min(1) @Max(1440) int paymentExpireMinutes,
        @Min(1) @Max(1440) int deliveryTimeoutMinutes,
        @Min(1) @Max(720) int autoConfirmHours,
        @Min(0) @Max(720) int settleCooldownHours
) {
}
```

要求：

1. 配置类必须使用构造器注入，禁止字段注入。
2. 必须加 `@Validated`，启动时暴露配置错误。
3. 生产密钥不得有默认值；本地默认值只能是安全值。
4. 配置前缀按业务域命名，不使用过宽的 `app`、`common`。
5. 单元测试验证非法配置启动失败。

### 4.2 Nacos 配置模板

配置继续参考现有模块，使用环境变量占位符：

```yaml
trade:
  order:
    fee-rate-percent: ${TRADE_FEE_RATE_PERCENT:2}
    min-fee: ${TRADE_MIN_FEE:0.01}
    payment-expire-minutes: ${TRADE_PAYMENT_EXPIRE_MINUTES:15}
    delivery-timeout-minutes: ${TRADE_DELIVERY_TIMEOUT_MINUTES:30}
    auto-confirm-hours: ${TRADE_AUTO_CONFIRM_HOURS:24}
    settle-cooldown-hours: ${TRADE_SETTLE_COOLDOWN_HOURS:24}
  payment-callback:
    secret: ${PAYMENT_CALLBACK_SECRET}
    secret-version: ${PAYMENT_CALLBACK_SECRET_VERSION:1}
    mock-enabled: ${PAYMENT_MOCK_ENABLED:false}
audit:
  delivery-mode: ${AUDIT_DELIVERY_MODE:ASYNC_MQ}
  rocketmq:
    topic: ${AUDIT_ROCKETMQ_TOPIC:audit-log-topic}
risk:
  command:
    poll-interval-seconds: ${RISK_COMMAND_POLL_INTERVAL_SECONDS:5}
```

密钥和敏感配置规则：

1. 只保存在 Nacos 加密配置或密钥管理系统。
2. 代码、文档和测试环境配置不得写生产密钥。
3. 配置变更要有审计记录。
4. 服务启动时输出配置摘要，不输出密钥值。

### 4.3 `Clock` 注入

每个 Spring 服务提供：

```java
@Bean
public Clock clock() {
    return Clock.systemDefaultZone();
}
```

业务服务通过构造器注入 `Clock`，替换 `LocalDateTime.now()`。

使用规则：

| 场景 | 规则 |
| --- | --- |
| 业务状态时间 | 使用 `LocalDateTime.now(clock)` |
| 日志时间 | 交给日志框架 |
| 超时计算 | 使用 `clock.instant()` 或统一 `LocalDateTime` |
| 单元测试 | 注入 `Clock.fixed` 或可控测试时钟 |
| 纯工具函数 | 允许使用系统时间，但不得写业务状态 |

收益：

1. 支付超时、自动确认、结算冷却等测试不再依赖 `Thread.sleep`。
2. 能精确测试跨天、跨小时和边界时间。
3. 避免不同服务实例时钟理解不一致，后续可统一改为 UTC 或明确的业务时区。

## 5. 订单编排服务拆分

### 5.1 现状职责

当前 `TradeOrderService` 覆盖：

1. 订单创建与取消。
2. 支付单查询、支付状态和回调处理。
3. 资产确认、卡密查看、交付记录。
4. 买家确认、结算和费率计算。
5. 售后、留言、证据、仲裁。
6. 订单评价。
7. 用户和管理端查询。
8. 支付超时、交付超时、自动确认、到期结算任务。
9. 风控事件记录和状态写入。

问题：

- 单类依赖多个 Mapper 和远程服务，测试构造复杂。
- 事务边界和业务职责混合，后续 Saga 迁移困难。
- 修改售后逻辑可能影响支付和结算。
- 代码审查难以判断影响范围。

### 5.2 目标结构

在 `mall-order-service` 内部拆分，不改变模块部署：

```text
com.example.item.service.order
    TradeOrderCommandService       创建、取消、确认
    TradeOrderQueryService         用户和管理端查询
    TradeOrderStateMachine         状态流转和权限校验
    TradeOrderSnapshotService      商品快照读写

com.example.item.service.payment
    PaymentCommandService          发起支付、超时关单
    PaymentCallbackService         回调校验与回调记录
    PaymentQueryService            支付单查询

com.example.item.service.delivery
    DeliveryCommandService         交付、手动内容校验
    DeliverySecretService          卡密查看、脱敏
    DeliveryEvidenceService        交付证据查询和提交

com.example.item.service.settlement
    OrderSettlementService         结算、费率、结算记录

com.example.item.service.dispute
    DisputeCommandService          发起售后、留言、仲裁
    DisputeQueryService            售后查询

com.example.item.service.review
    OrderReviewService             订单评价

com.example.item.service.risk
    OrderRiskEventService          风控事件组装和确认
    OrderRiskStateWriter           订单风险状态写入

com.example.item.orchestration
    TradeOrchestrationService      跨服务 Saga 编排入口
    TradeOrchestrationRecoverService 恢复、补偿和人工处理
```

Controller 拆分：

```text
TradeOrderController        /api/trade/orders
PaymentController           /api/payment
DisputeController           /api/trade/disputes
AdminDisputeController      /api/trade/admin/disputes
TradeOrderJobHandler        XXL-Job 入口
```

### 5.3 职责边界

| 服务 | 允许职责 | 禁止职责 |
| --- | --- | --- |
| `TradeOrderCommandService` | 订单本地状态、状态流转、创建取消 | 直接调用资产和资金远程服务 |
| `PaymentCallbackService` | 回调安全校验、回调落库、创建编排任务 | 直接完成资金冻结和资产确认 |
| `DeliveryCommandService` | 交付内容校验、交付记录 | 直接修改库存或卡密 |
| `OrderSettlementService` | 结算金额计算、结算记录 | 长事务内远程打款 |
| `TradeOrchestrationService` | 跨服务步骤编排、幂等和补偿 | 拼接 HTTP 响应 |
| `TradeOrderQueryService` | 查询、分页、权限过滤 | 隐式创建或修改数据 |

### 5.4 事务边界

结合第一批 Saga 设计，目标事务规则：

1. 本地事务只保存订单、支付、任务和状态日志。
2. 远程调用发生在事务提交后，由编排任务驱动。
3. 编排步骤必须先落任务再执行。
4. 失败步骤进入重试或补偿，不通过抛异常回滚已提交任务。
5. 查询服务不加 `@Transactional`，只读操作使用明确查询 SQL。
6. 需要多个本地表一致写入时，事务范围控制在一个 Service 方法内，不跨服务。

### 5.5 迁移策略

采用“门面保留 + 内部拆分”的方式：

```java
@Deprecated
@Service
public class TradeOrderFacade {
    private final TradeOrderCommandService commandService;
    private final PaymentCallbackService paymentCallbackService;
    private final DisputeCommandService disputeCommandService;

    public TradeOrder create(Long buyerId, Long itemId, Integer quantity) {
        return commandService.create(buyerId, itemId, quantity);
    }
}
```

迁移步骤：

| 步骤 | 内容 | 兼容策略 |
| --- | --- | --- |
| O-01 | 提取状态机和快照服务 | 原方法改为委托新服务 |
| O-02 | 提取查询服务 | Controller 逐步切换，路径不变 |
| O-03 | 提取交付和证据服务 | 保持原方法签名 |
| O-04 | 提取售后和评价服务 | 保持原接口契约 |
| O-05 | 提取结算服务 | 结算金额结果保持一致 |
| O-06 | 提取支付回调服务 | 第一批安全方案在此落地 |
| O-07 | 引入编排服务 | 先灰度少量任务类型 |
| O-08 | 删除门面和重复逻辑 | 所有测试通过后 |

回滚策略：

1. 每一步只改内部调用，不改数据库。
2. Controller 切换可通过单独提交回滚。
3. Saga 编排按任务类型灰度，失败可回退同步流程。
4. 门面保留到两个完整迭代后再删除。

## 6. DTO 与 Bean Validation

### 6.1 DTO 规范

替换 Controller 中的 `Map<String, Object>` 和 `Map<String, String>`：

```text
mall-api/src/main/java/api/trade/request/
    CreateOrderRequest.java
    CancelOrderRequest.java
    DeliverOrderRequest.java
    ReviewOrderRequest.java
    OpenDisputeRequest.java
    ArbitrateDisputeRequest.java
    PaymentCallbackRequest.java

mall-api/src/main/java/api/trade/response/
    TradeOrderDTO.java
    PaymentOrderDTO.java
    DeliveryRecordDTO.java
    DisputeDTO.java
    OrderSettlementDTO.java
```

请求 DTO 示例：

```java
public record CreateOrderRequest(
        @NotNull @Positive Long itemId,
        @NotNull @Min(1) @Max(10) Integer quantity
) {
}
```

规范：

1. Controller 参数必须加 `@Valid`。
2. 请求和响应 DTO 分开定义，不复用实体。
3. 金额使用 `BigDecimal`，禁止 `Double` / `Float`。
4. 时间使用 `LocalDateTime`，序列化格式由全局 ObjectMapper 统一。
5. 长度、范围、枚举和必填规则必须落在注解上。
6. 复杂校验放在领域服务，错误仍走统一异常体系。

### 6.2 响应模型

统一响应外壳：

```java
public record ApiResponse<T>(
        int code,
        String msg,
        T data,
        String traceId
) {
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(200, "success", data, TraceContext.getTraceId());
    }
}
```

兼容策略：

1. 新接口直接返回 `ApiResponse<T>`。
2. 旧接口返回 `Map` 时统一由 `GlobalExceptionHandler` 处理异常。
3. 分阶段把旧 Controller 改为 `ApiResponse<T>`，不改变 `code`、`msg`、`data` 字段名。
4. `traceId` 是新增字段，前端向后兼容。

## 7. 异常、错误码与日志

### 7.1 异常体系

在 `mall-api` 定义：

```text
api/exception/
    BizException.java
    BadRequestException.java
    UnauthorizedException.java
    ForbiddenException.java
    NotFoundException.java
    StateConflictException.java
    RemoteDependencyException.java
    ValidationException.java
```

基类：

```java
public abstract class BizException extends RuntimeException {
    private final String errorCode;
    private final Map<String, Object> context;

    protected BizException(String errorCode, String message, Map<String, Object> context) {
        super(message);
        this.errorCode = errorCode;
        this.context = context == null ? Map.of() : Map.copyOf(context);
    }
}
```

使用规则：

1. 业务异常必须携带稳定错误码。
2. Controller 不允许 `catch (Exception e)` 后返回业务响应。
3. Service 抛业务异常，不拼接用户提示和 HTTP 状态。
4. 远程调用失败包装为 `RemoteDependencyException`，包含下游服务和幂等键。
5. 状态冲突使用 `StateConflictException`，提示刷新或重试。

### 7.2 错误码命名

格式：

```text
{MODULE}_{DOMAIN}_{ACTION}_{REASON}
```

示例：

| 错误码 | HTTP | 说明 |
| --- | --- | --- |
| `TRADE_ORDER_CREATE_STOCK_INSUFFICIENT` | 409 | 库存不足 |
| `TRADE_ORDER_STATE_INVALID` | 409 | 订单状态不允许操作 |
| `TRADE_ORDER_OWNER_MISMATCH` | 403 | 无权操作订单 |
| `TRADE_PAYMENT_CALLBACK_SIGNATURE_INVALID` | 401 | 支付回调签名错误 |
| `TRADE_PAYMENT_CALLBACK_NONCE_DUPLICATED` | 409 | nonce 重复 |
| `TRADE_DISPUTE_ALREADY_RESOLVED` | 409 | 售后已处理 |
| `RISK_CASE_STATE_CHANGED` | 409 | 案件状态已变化 |
| `RISK_COMMAND_DEAD_LETTER` | 409 | 命令进入死信 |
| `COMMON_REQUEST_PARAM_INVALID` | 400 | 参数非法 |
| `COMMON_SYSTEM_ERROR` | 500 | 系统错误 |

要求：

1. 错误码一旦对外使用，不得修改含义。
2. 前端只根据错误码决定交互，不解析中文文案。
3. 5xx 错误对用户统一显示“系统繁忙”，详细信息只进入日志。
4. 错误码集中定义，禁止散落字符串。

### 7.3 全局异常处理

每个 MVC 服务提供：

```java
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException e) {
        return ResponseEntity.badRequest().body(ApiResponse.error(
                "COMMON_REQUEST_PARAM_INVALID",
                firstValidationMessage(e),
                TraceContext.getTraceId()
        ));
    }

    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<Void>> handleBiz(BizException e) {
        return ResponseEntity.status(resolveStatus(e))
                .body(ApiResponse.error(e.getErrorCode(), e.getMessage(), TraceContext.getTraceId()));
    }
}
```

处理范围：

| 异常 | HTTP | 响应 |
| --- | --- | --- |
| 参数校验失败 | 400 | 返回字段级错误 |
| 业务参数非法 | 400 | 稳定错误码 |
| 未登录 | 401 | 提示重新登录 |
| 无权限 | 403 | 不泄露资源存在性 |
| 资源不存在 | 404 | 稳定错误码 |
| 状态冲突 | 409 | 提示刷新或重试 |
| 下游超时 | 502 / 504 | 可重试提示 |
| 未知异常 | 500 | 系统繁忙，不返回堆栈 |

### 7.4 traceId 贯通

网关已经生成或透传 `X-Request-Id` 和 `traceparent`。业务服务增加：

```java
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceContextFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        try {
            TraceContext.set(extractTraceId(request));
            MDC.put("traceId", TraceContext.get());
            response.setHeader("X-Request-Id", TraceContext.get());
            chain.doFilter(request, response);
        } finally {
            MDC.remove("traceId");
            TraceContext.clear();
        }
    }
}
```

要求：

1. traceId 只接受合法 UUID 或 W3C traceparent 中的 trace-id。
2. 响应头返回网关 request id，方便用户反馈问题。
3. Dubbo、RocketMQ 和异步线程池需要传递 trace 上下文。
4. 审计日志中的 traceId 与业务日志一致。
5. 不得把 traceId 当作用户 ID 或幂等键。

### 7.5 日志规范

统一使用 SLF4J：

```java
log.info("Order created: orderNo={}, buyerId={}, itemId={}, quantity={}",
        orderNo, buyerId, itemId, quantity);
```

要求：

1. 使用占位符，不使用字符串拼接。
2. 关键链路日志必须包含 `traceId`、业务单号和操作类型。
3. 禁止输出密钥、签名原文、卡密、身份证、手机号和完整回调报文。
4. 5xx 记录异常堆栈，4xx 只记录摘要。
5. 业务成功走 info，详细诊断走 debug，告警走 warn，系统错误走 error。
6. 不使用 `System.out.println`、`printStackTrace` 和空的 `catch (Exception ignored)`。

建议日志字段：

```json
{
  "timestamp": "2026-09-30T10:00:00.000+08:00",
  "level": "INFO",
  "service": "mall-order-service",
  "traceId": "6f9f8f9b0c1d4e7f",
  "orderNo": "TR123",
  "event": "ORDER_CREATED",
  "operatorId": 1001,
  "costMs": 35
}
```

## 8. 测试接口生产暴露治理

### 8.1 现状风险

主要风险点：

1. `MockPaymentController` 的 mock 支付能力在主代码中，无环境限制。
2. `AuditLogTestController` 提供 `/api/audit/test-*`、`recent-logs` 和 benchmark。
3. `UserController` 响应中返回 `debugCode`。
4. 网关虽然限制了 `/api/audit/**` 为 ADMIN，但服务直连或配置遗漏时仍可能暴露。

### 8.2 治理方案

#### Mock 支付

保留生产回调：

```text
POST /api/payment/callback
```

限制开发接口：

```text
POST /api/payment/{paymentNo}/mock-pay
```

实现要求：

1. `mock-pay` 使用 `@Profile({"local", "dev", "test"})` 或配置开关。
2. 生产环境 Bean 不创建，接口返回 404。
3. Nacos 配置 `trade.payment-callback.mock-enabled=false` 作为第二道防线。
4. 网关生产配置不放行 mock 路径。
5. 代码扫描禁止生产模块引用 `MockPaymentController`。

#### 审计演示与压测

`AuditLogTestController` 处理：

1. 将演示 Controller 移到测试源码或 `dev` profile Bean。
2. 生产路径改为：

```text
GET /api/internal/dev/audit/**
```

3. `internal` 路径默认拒绝，仅显式配置 allowlist 后可用。
4. benchmark 不允许通过 HTTP 触发，改为 JMH 或测试代码。
5. `recent-logs` 改为受权限控制的正式审计查询接口。

#### debugCode

1. 删除用户注册、登录等响应中的 `debugCode`。
2. 验证码只写入日志或审计表，不返回给客户端。
3. 生产环境只提示“验证码已发送”。
4. 如测试环境需要 debug，使用 profile 专属 Serializer 或独立测试接口。

### 8.3 服务直连防线

仅靠网关不足，服务内也必须有防线：

1. 各服务只监听内网地址。
2. `/api/internal/**` 增加本地安全过滤器。
3. 生产 profile 下禁用演示 Bean。
4. 内部接口要求独立内部令牌或 mTLS。
5. CI 扫描生产源码中的 `@RequestMapping("/api/test")`、`debugCode`、`mock-pay` 等风险词。

## 9. 工程规范与 CI

### 9.1 Java 规范

| 项 | 要求 |
| --- | --- |
| 命名 | 类名名词、方法名动词、包名小写单数 |
| 依赖注入 | 构造器注入，禁止字段注入 |
| 类长度 | 目标不超过 500 行 |
| 方法长度 | 目标不超过 60 行 |
| 构造器依赖 | 目标不超过 7 个，超过时拆分 |
| 事务 | 方法名或注释明确事务边界 |
| 集合 | 优先 `List.of`、`Map.of` 不可变集合 |
| 时间 | 业务时间来自 `Clock` |
| 金额 | 使用 `BigDecimal` |
| 异常 | 不吞异常，不返回堆栈 |
| SQL | 状态和动态条件参数化 |

### 9.2 前端规范

1. API 请求集中在 `src/api`，组件不直接 `fetch`。
2. 组件目标不超过 300 行，超过时拆子组件或 Hook。
3. 异步状态显式建模，不用布尔值混用加载和错误。
4. 列表必须有 loading、empty、error 三态。
5. 危险操作必须有确认和禁用状态。
6. 文案集中管理，状态文案来自字典。
7. 用户输入先本地校验，再提交后端。

### 9.3 提交与分支规范

分支：

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

### 9.4 CI 检查

最小流水线：

```text
1. git diff --check
2. mvn -pl mall-api,mall-order-service,mall-risk-service test
3. npm --prefix mall-frontend run build
4. ArchUnit / 自定义扫描
5. 敏感信息扫描
6. 生产测试接口扫描
```

扫描规则：

| 规则 | 禁止 |
| --- | --- |
| 状态字面量 | `new` 代码新增 `"WAIT_PAY"` 等字符串 |
| Controller 输入 | 新 Controller 方法使用 `Map` 请求体 |
| 注入方式 | 字段注入 `@Autowired` |
| 异常处理 | Controller `catch (Exception e)` 返回响应 |
| 时间 | Service 新增 `LocalDateTime.now()` |
| 输出 | `System.out.println`、`printStackTrace` |
| 测试接口 | 生产源码出现未加 profile 的 mock/test controller |

### 9.5 Code Review 检查清单

后端：

1. 是否有明确需求和验收标准？
2. 状态流转是否使用枚举和矩阵？
3. 事务内是否有远程调用？
4. 远程操作是否有幂等键和补偿？
5. 参数是否有 DTO 和 Bean Validation？
6. 错误码是否稳定，HTTP 语义是否正确？
7. 日志是否包含 traceId 和业务单号，是否脱敏？
8. 单测是否覆盖边界、并发和失败路径？

前端：

1. 是否处理 loading、empty、error、retry？
2. 请求是否可取消，是否防止旧响应覆盖新状态？
3. 操作是否有并发禁用？
4. 危险操作是否有确认？
5. 状态文案是否来自字典？
6. 移动端和键盘可访问性是否可用？

## 10. 开发任务拆分

| 序号 | 任务 | 主要内容 | 依赖 |
| --- | --- | --- | --- |
| E-01 | 公共契约 | `ApiResponse`、`PageResult`、错误码、异常、用户和 trace 上下文 | 无 |
| E-02 | Web 基础设施 | `TraceContextFilter`、`GlobalExceptionHandler` | E-01 |
| E-03 | 状态枚举 | 新增枚举、流转矩阵和测试 | E-01 |
| E-04 | 配置类 | 迁移 `@Value` 到 `@ConfigurationProperties` | 无 |
| E-05 | `Clock` | 提供Bean并替换业务时间调用 | 无 |
| E-06 | DTO 改造 | 逐 Controller 替换 Map 入参 | E-01 |
| E-07 | 订单状态机 | 提取状态机和快照服务 | E-03 |
| E-08 | 订单查询拆分 | 查询、分页、权限过滤 | E-06 |
| E-09 | 交付和证据拆分 | 交付、卡密、证据服务 | E-07 |
| E-10 | 售后评价拆分 | 售后、仲裁、评价服务 | E-07 |
| E-11 | 支付和结算拆分 | 回调、支付查询、结算服务 | 第一批安全方案 |
| E-12 | Saga 编排接入 | 接入第一批编排任务模型 | E-11 |
| E-13 | 测试接口治理 | profile、配置开关、路径迁移和扫描 | 无 |
| E-14 | CI 与规范 | Checkstyle、ArchUnit、扫描和 CR 清单 | E-01 到 E-13 |

建议按 4 个小版本发布：

1. **V1 契约层**：E-01、E-02、E-13。
2. **V2 基础治理**：E-03、E-04、E-05、E-06。
3. **V3 订单拆分**：E-07 到 E-11。
4. **V4 编排与门禁**：E-12、E-14。

## 11. 测试设计

### 11.1 架构测试

使用 ArchUnit 或等价规则固化：

1. Controller 方法不得返回 `Map`。
2. Controller 不得出现 `catch (Exception e)` 后包装响应。
3. Service 不得使用字段注入。
4. `com.example.item.service.order` 不得直接依赖 HTTP 客户端。
5. 生产源码不得出现无 profile 限制的测试 Controller。

### 11.2 单元测试

| 测试 | 重点 |
| --- | --- |
| 枚举测试 | code 序列化、反序列化、非法值、状态矩阵 |
| 配置测试 | 非法值启动失败，默认值安全 |
| 时间测试 | 使用固定 Clock 验证超时和冷却 |
| 异常测试 | HTTP 状态、错误码、traceId、脱敏 |
| 订单拆分测试 | 每个新服务只测试自身职责 |
| 回调测试 | 第一批安全测试在新服务上保持通过 |

### 11.3 回归测试

1. 原有 `TradeOrderServiceTest` 中的核心用例迁移到对应新服务。
2. 保留一个门面集成测试，确保迁移前后行为一致。
3. 支付、交付、售后、结算四条主链路跑端到端冒烟。
4. 前端交易工作台和风控工作台跑关键路径 E2E。

### 11.4 安全测试

1. 生产 profile 下访问 mock 支付返回 404。
2. 生产 profile 下访问审计测试接口返回 404。
3. 登录注册响应不包含 `debugCode`。
4. 5xx 响应不包含堆栈和内部地址。
5. 日志和审计不包含密钥、卡密和敏感明文。

## 12. 发布与回滚

发布要求：

1. 每个阶段独立提交，禁止把契约、拆分和配置混在一个大提交。
2. 状态枚举和 DTO 改造先在低风险模块试点，再推广订单服务。
3. 订单服务拆分完成后保留门面两个迭代。
4. Saga 接入按任务类型灰度，并保留同步回退开关。
5. CI 门禁先 warn 后 enforce，给存量代码一个修复窗口。

回滚策略：

1. 公共契约新增字段可回滚，不删除已有字段。
2. 状态枚举回滚不影响数据库 code。
3. 配置类回滚时保留 Nacos key 映射，避免配置丢失。
4. 服务拆分可通过门面回退到旧调用。
5. 测试接口治理可通过 profile 配置快速关闭。

## 13. 验收标准

1. 核心状态全部由枚举定义，新增状态必须修改流转矩阵并有测试。
2. `TradeOrderService` 不再承担多领域职责，各新服务职责边界清晰。
3. Controller 不再接收 `Map` 请求体，参数校验由 Bean Validation 完成。
4. 所有服务统一异常处理、错误码、HTTP 语义和响应外壳。
5. 网关与业务服务 traceId 贯通，日志可按 traceId 检索。
6. 业务时间通过 `Clock` 注入，关键超时和冷却场景可确定性测试。
7. 配置统一使用 `@ConfigurationProperties` 并具备启动校验。
8. 生产环境不存在可访问的 mock 支付、审计压测和 debug 验证码接口。
9. CI 具备格式、测试、架构和风险词扫描。
10. 核心业务回归、安全回归和前端关键路径测试全部通过。

