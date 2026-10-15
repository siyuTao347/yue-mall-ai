# 用户端“我的订单”页面详细设计

> 版本：1.0 · 日期：2026-10-15 · 状态：待实施设计
>
> 对应用户新增要求：用户端新增一个订单显示页面。本文定义独立订单列表、详情、状态查询和必要操作；当前交付为设计文档，页面代码尚未实施。
>
> 关联：[购物、下单与交易一致性改造设计](/Users/yu/IdeaProjects/mall-parent/doc/improve/shopping_order_improvement_design.md)。涉及支付、库存、售后等状态规则以主文档为准。

## 1. 目标与现状

用户能够从顶部导航或个人菜单进入“我的订单”，快速找到买过的商品，查看支付、交付、售后进度；刷新和重新登录后仍可定位订单。卖家能够切换“我卖出的”，不会把买家操作和卖家操作混在一起。

当前已有基础：

- [App.jsx](/Users/yu/IdeaProjects/mall-parent/mall-frontend/src/App.jsx:21) 将商城与担保工作台全部放在首页，没有独立订单路由。
- [Navbar.jsx](/Users/yu/IdeaProjects/mall-parent/mall-frontend/src/components/layout/Navbar.jsx:103) 的用户导航使用首页锚点，个人菜单尚无“我的订单”。
- [TradeWorkbench.jsx](/Users/yu/IdeaProjects/mall-parent/mall-frontend/src/components/trade/TradeWorkbench.jsx:56) 有订单列表、分页和操作，但与卖家中心、账户资金混在一个组件中，部分状态仍直接显示英文枚举。
- [tradeApi.js](/Users/yu/IdeaProjects/mall-parent/mall-frontend/src/api/tradeApi.js:68) 已封装列表、取消、支付、交付、确认和售后；缺少供独立详情使用的完整封装。
- [TradeOrderController](/Users/yu/IdeaProjects/mall-parent/mall-order-service/src/main/java/com/example/item/controller/TradeOrderController.java:72) 已提供分页列表、详情、状态日志和售后详情；列表默认返回本人买入或卖出的订单。

因此采用“复用后端交易能力、拆分前端页面、扩展用户查询 DTO”的实现方式。新页面不调用旧 `/api/order/pay`、`createTcc` 或前端积分模拟接口。

## 2. 页面范围与导航

### 2.1 必做功能

1. 顶部导航、用户下拉菜单增加“我的订单”；订单创建受理后显示“查看订单”入口。
2. 独立列表页面：买入/卖出视角、状态标签、订单号搜索、时间筛选、分页。
3. 独立详情页面：商品快照、订单与支付信息、交付、售后、状态时间线、允许执行的操作。
4. 完整处理加载、空数据、无权限、网络失败、处理中、账号切换和未知状态。
5. 后端驱动操作权限和可执行状态；操作受理后持续查询，确认终态后再展示完成。

### 2.2 路由方案

当前项目只有 React/ReactDOM，尚未引入路由库。首期可使用统一 hash 路由，避免要求部署服务器额外配置 SPA 回退；应集中实现解析与导航，不在每个组件独立处理 `window.location`。

| 地址 | 页面 | 说明 |
| --- | --- | --- |
| `/#/home` | 商城首页 | 保留商品、秒杀和卖家/资金入口 |
| `/#/orders?view=BUYER&tab=ALL&page=1` | 我的订单 | 默认“我买到的” |
| `/#/orders?view=SELLER&tab=WAIT_DELIVERY&page=1` | 我卖出的 | 卖家交付视角 |
| `/#/orders/{orderNo}` | 订单详情 | 支持直接打开和刷新 |

兼容现有 `#tradeSection`、`#seckillSection`、`#catalogSection`：统一导航层将其解释为返回首页后滚动到对应区域。未知路由显示返回首页入口。管理员继续进入管理后台壳层，不因 hash 切换获得普通用户额外权限。

若实施时选择引入成熟路由库，使用同样路径和查询参数契约；不要同时维护互不一致的两套路由状态。

### 2.3 登录与页面边界

- 未登录打开订单页：显示登录引导，保存本地站内 returnTo；登录成功回到原列表筛选或订单详情。
- returnTo 只接受本站已知路由，不能任意重定向外部地址。
- 用户退出/切换：立即取消请求、清空列表/详情/卡密缓存与轮询，旧账号迟到响应不得写入新账号状态。
- 请求缓存键包含 userId、view、筛选条件和页码；不在浏览器持久化明文交付内容。
- 从详情返回列表恢复筛选、页码与滚动位置；刷新详情按 orderNo 重新获取服务器数据。

## 3. 页面布局

### 3.1 列表页线框

```text
顶部导航：商城首页   限时秒杀   我的订单             用户菜单

我的订单                                      [刷新]
[我买到的] [我卖出的]

[全部] [待支付] [处理中] [待交付] [待确认] [已完成] [售后/退款] [已关闭]

订单号 [________________]  时间 [近30天 v]  [查询] [重置]

订单 TR…   下单时间 2026-10-15 14:00          待支付
┌商品图片┐  商品快照名称 / 交付方式 / 卖家昵称
└────────┘  单价 ¥99.00 × 1           订单金额 ¥99.00
支付截止 14:15，剩余 08:23                [去支付] [取消] [详情]

订单 TR…   下单时间 …                         支付确认中
商品信息…                             [查看进度] [详情]

                     上一页   1 / 8   下一页
```

状态数量来自服务端汇总；未加载时不显示虚构的 0。首期可不显示数量，但不能把当前页记录数当总数。

### 3.2 列表卡片字段

| 区域 | 字段 | 展示规则 |
| --- | --- | --- |
| 标识 | orderNo、source、createdTime | 订单号可复制；普通/秒杀/历史来源使用中文标签 |
| 商品 | name、imageUrl、assetType、deliveryMode | 来自成交快照；旧单缺字段显示“历史商品信息缺失” |
| 交易对象 | sellerDisplayName / buyerDisplayName | 根据视角切换；不显示不必要的手机号、邮箱 |
| 金额 | unitPrice、quantity、orderAmount、paidAmount、currency | 金额由服务器返回十进制字符串；未知金额显示“确认中” |
| 状态 | displayStatus、statusDescription、processingSince | 一个主状态，售后/风险有明确辅助提示 |
| 时限 | payDeadline、deliveryDeadline、autoConfirmTime | 基于 serverTime 校正，倒计时归零后重新查询 |
| 操作 | allowedActions、blockedReasons | 服务端决定，客户端防重点击 |

金额表达应区分“订单金额”“已支付”“退款金额”；CREATE_PENDING 不显示 `¥0.00` 造成免费成交误解。卖家手续费和预计收入可在卖家详情显示，买家列表不暴露无关资金字段。

### 3.3 详情页

详情顺序为：

1. 返回列表、订单号、主状态与当前下一步提示。
2. 商品成交快照、购买数量、交易对象、订单来源。
3. 金额区：单价、订单金额、实际已付、已退金额；卖家视角附费用与收入。
4. 时间区：创建、支付截止、支付确认、交付、确认、售后、退款/完成。
5. 交付区：交付方式、交付状态、查看/复制操作。明文卡密需要用户显式操作后才加载。
6. 售后区：申请原因、当前处理状态、证据/留言、退款结果及去向。
7. 用户可理解的时间线：仅展示业务动作，不暴露内部任务名、堆栈或风险规则编号。
8. 底部操作区：按 allowedActions 展示主要按钮；手机端可吸底，保留正文空间。

状态日志和交付内容独立加载；时间线失败不能使整页订单信息消失。无权查看与订单不存在使用统一的“订单不存在或无权查看”页面。

## 4. 状态分组与文案

订单状态、支付状态、售后状态和风险状态是多个维度，不能仅把某个英文枚举转中文就决定所有按钮。后端查询层产出统一 `displayStatus/statusGroup`，前端按已知字典展示，未知状态显示“状态更新中，请刷新”并隐藏写操作。

### 4.1 列表筛选组

按以下优先顺序分类，确保一单在同一视角的状态组中只计数一次：

1. 有有效售后，或处于 REFUNDING/REFUNDED → AFTER_SALE。
2. CANCELLED/CLOSED/REJECTED → CLOSED。
3. CREATE_PENDING/PAY_CONFIRMING/CANCELLING → PROCESSING。
4. WAIT_PAY → WAIT_PAY。
5. PAID → WAIT_DELIVERY。
6. DELIVERED → WAIT_CONFIRM。
7. CONFIRMED/SETTLING/SETTLED → COMPLETED（买家已完成收货确认，详情继续区分结算阶段）。

`ALL` 包含以上所有组；不存在的枚举归 OTHER 并计入 ALL，不能静默漏单。风险状态作为附加提示及操作约束，不把“冻结”错误等同“退款完成”。卖家视角中 CONFIRMED/SETTLING 展示“待结算/结算中”，SETTLED 展示“已结算”。

### 4.2 关键文案

| 后端事实 | 用户文案 | 不应出现的误导 |
| --- | --- | --- |
| CREATE_PENDING | 订单创建中，正在确认商品与库存 | “下单完成，立即支付” |
| WAIT_PAY 且支付尝试有效 | 待支付，可在截止前继续支付 | 只凭本地时间宣布订单关闭 |
| PAY_CONFIRMING | 支付结果确认中，请稍候 | “资金已进入托管” |
| CANCELLING | 正在取消，等待库存释放 | “订单已关闭” |
| PAID | 已支付，等待交付 | “已收到商品” |
| REFUNDING | 退款处理中 | “退款已到账” |
| REFUNDED | 已退款至平台余额/实际目标 | 未接渠道退款却显示“原路退款” |
| 可信迟到支付待补偿 | 订单已关闭，支付款项处理中 | 再次允许购买原先释放的资产 |
| 网络超时、结果未知 | 请求结果确认中，可查询最新进度 | 虚构成功或引导重复生成新订单 |

## 5. 操作与权限

新增 `OrderActionPolicy` 在服务端集中计算可操作项；所有命令服务仍独立校验身份、状态、风险和版本。allowedActions 是展示建议，不能替代命令端鉴权。

| 操作 | 身份与主要前置条件 | 交互/后续 |
| --- | --- | --- |
| 支付 | 买家，WAIT_PAY，未超时，风险允许，无已确认支付 | 显示明确金额和 Mock 标识；受理后查询确认进度 |
| 取消 | 买家，允许取消的未支付状态 | 二次确认；受理后显示取消中，终态后移出待支付 |
| 卖家交付 | 卖家，人工交付且 PAID，风险/售后允许 | 输入内容并校验；避免与自动卡密交付重复 |
| 查看交付 | 买家，达到允许披露状态，风险/售后允许 | 显式点击，产生披露记录；不在列表自动请求 |
| 确认收货 | 买家，DELIVERED，无活动售后，风险允许 | 提示确认后进入结算流程，用户明确确认 |
| 发起售后 | 订单参与者，服务端判定可发起 | 选择原因、输入说明、提交证据；不使用固定文本代替用户选择 |
| 评价 | 买家，已达到评价阶段且未评价 | 评分与正文由用户填写；不固定五星和预制好评 |
| 查看进度 | 订单参与者 | 查询服务器状态，无写入副作用 |

每个订单有独立 busy 状态，同一订单处理中不能重复提交；不同订单可以独立查询。发生 409 状态变化时刷新详情，解释“订单状态已变化”；鉴权失败要求重新登录。

服务端响应包含 `operationNo/state/orderVersion`。客户端提交超时保留原幂等键，先查询，不生成替代单号。成功提示只在返回明确同步完成或查询到对应终态后出现。

## 6. 后端接口设计

### 6.1 列表扩展

沿用 `GET /api/trade/orders`，兼容已有 `page/pageSize/status/disputeStatus/fromTime/toTime`，新增：

| 参数 | 规则 |
| --- | --- |
| view | BUYER/SELLER；新版用户页默认 BUYER。旧调用未传时过渡保留原参与者查询 |
| statusGroup | ALL/WAIT_PAY/PROCESSING/WAIT_DELIVERY/WAIT_CONFIRM/COMPLETED/AFTER_SALE/CLOSED |
| orderNo | 精确匹配，限制长度；不做无索引任意模糊扫描 |
| source | 可选 NORMAL/SECKILL/LEGACY；无值表示全部可见来源 |
| fromTime/toTime | 服务器校验时间范围，上限沿用现有 92 天限制；历史按窗口查询 |

普通用户身份完全来自认证上下文。BUYER 用 buyer_id=currentUser；SELLER 用 seller_id=currentUser；忽略或拒绝普通用户传入 userId/merchantId。权限条件必须与所有筛选条件按 AND 组合，避免 OR 改写后泄露其他订单。

已有 PageResult 格式继续使用 `records/page/pageSize/total/hasMore`。默认页大小 20、最大 100 沿用现有配置；排序保持 `(created_time DESC,id DESC)`。分页统计、状态计数应用同一可见性过滤。

`status` 与 `statusGroup` 同传时建议返回参数冲突，避免语义不明；状态组在服务端下推 SQL，不能查一页再由前端过滤形成空页和错误总数。

### 6.2 用户列表 DTO

```json
{
  "orderNo": "TR_example",
  "orderVersion": 5,
  "viewRole": "BUYER",
  "source": "NORMAL",
  "item": {
    "itemId": "1001",
    "name": "示例虚拟商品",
    "imageUrl": null,
    "deliveryMode": "AUTO_CARD"
  },
  "counterparty": { "displayName": "示例卖家" },
  "quantity": 1,
  "unitPrice": "99.00",
  "orderAmount": "99.00",
  "paidAmount": "0.00",
  "refundedAmount": "0.00",
  "currency": "CNY",
  "priceConfirmed": true,
  "orderStatus": "WAIT_PAY",
  "payStatus": "INIT",
  "disputeStatus": "NONE",
  "statusGroup": "WAIT_PAY",
  "displayStatus": "WAIT_PAY",
  "statusDescription": "请在支付截止前完成支付",
  "paymentNo": "PAY_example",
  "allowedActions": ["PAY", "CANCEL", "VIEW_DETAIL"],
  "blockedReasons": {},
  "payDeadline": "2026-10-15T14:15:00+08:00",
  "createdTime": "2026-10-15T14:00:00+08:00"
}
```

补充约束：

- Java Long 标识符在新 DTO 中以字符串传输，避免 JavaScript 精度损失；订单号始终为字符串。
- itemSnapshot 在后端安全解析为白名单字段，不让各页面重复解析任意 JSON；旧快照缺字段提供明确缺失值。
- 响应 envelope 增加 serverTime，时间使用带时区的 ISO 8601。现有 LocalDateTime 接口兼容期明确为 Asia/Shanghai，不能由浏览器猜测时区。
- 不返回签名信息、内部风险原因、卡密密文或明文。支付操作所需的 paymentNo 可返回，签名令牌不可进入列表。

### 6.3 详情、汇总与时间线

| 接口 | 处理 |
| --- | --- |
| GET `/api/trade/orders/{orderNo}` | 用 UserOrderDetailDTO 替代原始实体；含列表字段、有效支付尝试摘要、交付/售后摘要、公开失败原因 |
| GET `/api/trade/orders/{orderNo}/logs` | 只返回公开业务时间线；先校验订单可见性 |
| GET `/api/trade/orders/summary` | 可选新增，按view/时间范围统计各状态组；不含其他用户数量 |
| GET `/api/trade/disputes/{disputeNo}` | 复用现有售后详情权限，补前端封装 |
| POST `/api/trade/orders/{orderNo}/reveal` | 按主文档实施披露授权，替代会产生查看副作用的GET |

列表不逐条 RPC 查询支付、卖家、风控和交付详情。优先使用订单快照/持久化展示字段，必要信息批量查询。allowedActions 计算使用订单已有状态和权限规则，写操作提交时再做最新权威校验。

### 6.4 历史订单展示

统一新订单切换前，用户可能同时拥有 `t_order` 与 `t_trade_order` 记录。提供受控历史查询适配，统一 source 和 displayId，历史订单注明“历史商城订单”。

两表字段不足以等价时只展示能证实的字段，未知交付/托管状态不伪造。历史订单默认只读，需要售后时提供业务联系入口。不能在浏览器各取 20 条后拼接，称为统一分页；应服务端 UNION 投影/读模型统一排序，或单独“历史订单”入口保持独立分页。

建议首期选择独立历史入口，减少迁移依赖；新普通商城和秒杀交易统一后自然进入主列表。确认旧数据仅为测试数据时，可通过配置关闭历史入口，但上线前需要完成数据盘点。

## 7. 前端实现拆分

在 `/Users/yu/IdeaProjects/mall-parent/mall-frontend/src` 下按以下职责新增模块，实施时沿用现有 CSS 变量、Navbar、Pagination、Toast 与认证弹窗。

| 文件/模块 | 职责 |
| --- | --- |
| `/Users/yu/IdeaProjects/mall-parent/mall-frontend/src/hooks/useStoreRoute.js` | 路由解析、导航、hashchange、旧锚点兼容、查询参数规范化 |
| `/Users/yu/IdeaProjects/mall-parent/mall-frontend/src/pages/orders/UserOrdersPage.jsx` | 订单页容器，视角、筛选、分页、加载/失败/空态 |
| `/Users/yu/IdeaProjects/mall-parent/mall-frontend/src/pages/orders/UserOrderDetailPage.jsx` | 详情路由、分区加载、操作后刷新 |
| `/Users/yu/IdeaProjects/mall-parent/mall-frontend/src/components/orders/OrderFilters.jsx` | 状态标签、订单号、时间范围 |
| `/Users/yu/IdeaProjects/mall-parent/mall-frontend/src/components/orders/OrderCard.jsx` | 商品快照、金额、状态、入口 |
| `/Users/yu/IdeaProjects/mall-parent/mall-frontend/src/components/orders/OrderActions.jsx` | allowedActions 渲染、确认对话框、防重提交 |
| `/Users/yu/IdeaProjects/mall-parent/mall-frontend/src/components/orders/OrderTimeline.jsx` | 用户业务时间线 |
| `/Users/yu/IdeaProjects/mall-parent/mall-frontend/src/hooks/useOrders.js` | 分页查询、取消旧请求、账号隔离 |
| `/Users/yu/IdeaProjects/mall-parent/mall-frontend/src/hooks/useOrderProgress.js` | 单订单/命令状态轮询与生命周期管理 |
| `/Users/yu/IdeaProjects/mall-parent/mall-frontend/src/utils/orderStatus.js` | 中文文案、视觉状态、未知枚举兜底 |
| `/Users/yu/IdeaProjects/mall-parent/mall-frontend/src/pages/orders/orders.css` | 桌面/移动布局、状态、骨架屏、空态 |

`tradeApi.order` 补充 detail、logs、summary 和查询 AbortSignal；已有 unwrap 显式适配受理状态。通用 request 层需将 401、403、409、业务失败与网络异常区分，保留错误码供页面使用。

原 TradeWorkbench 逐步聚焦卖家与资金能力，担保订单区改为独立页入口或轻量最近订单摘要。共享动作逻辑抽成 hook/组件，避免新旧页面各写一份支付/取消流程。

## 8. 查询、轮询与异常处理

### 8.1 查询状态

区分 `idle/loading/success/empty/error/refreshing`。首次错误显示重试；已有数据刷新失败保留列表并标记“刷新失败，显示上次结果”，不得清成“暂无订单”。

筛选变化先归页码 1；搜索按提交触发或合理防抖。使用 AbortController 或请求序号，保证旧筛选的慢响应不会覆盖新筛选结果。页码超出总页数时回到最后有效页并重新查询。

### 8.2 异步进度

- 仅当前详情或当前页存在 CREATE_PENDING、PAY_CONFIRMING、CANCELLING、REFUNDING 等状态时查询进度。
- 建议 2 秒起步，逐步退避至 10 秒，加入抖动；一次完成后再安排下一次，避免 setInterval 请求重叠。
- 优先批量刷新当前页状态，避免 20 张卡片各自无限轮询。服务端可后续提供批量进度接口。
- 页面隐藏时暂停高频查询；恢复可见/网络重连时刷新一次。到明确终态、退出、切路由时停止。
- 连续查询超过建议 2 分钟仍未完成，提示“处理时间较长，订单已保留”，展示订单号和手动刷新；不将超时直接当业务失败。
- 首期轮询即可；后续 SSE 只推订单号、版本、状态，不推敏感交付内容，收到通知后按权限重新查询。

## 9. 视觉、可访问性与体验细节

- 复用现有站点颜色、间距和按钮样式；页面信息优先级为状态→商品→金额→下一步。
- 状态同时用文字和颜色，不能只靠红绿区分；按钮有键盘焦点，Tab/Enter 可完成筛选和操作。
- 桌面列表以卡片/信息行组织；手机使用单列，金额和主操作保持可见，长订单号可换行/复制。
- 加载骨架保持高度，防止筛选/分页跳动。空状态分“还没有订单”和“没有匹配结果”，后者提供清除筛选。
- 错误提示说明可采取的动作；风控审核仅展示用户可理解的等待/联系处理提示。
- 自定义评价、售后和交付表单校验长度/必填项，服务端再次校验；渲染用户内容时作为纯文本，避免 HTML 注入。

## 10. 开发顺序与依赖

| 工作项 | 内容 | 依赖 |
| --- | --- | --- |
| UI-01 | 页面路由、导航、登录回跳、列表壳层 | 无交易写接口依赖，可先做 |
| API-01 | 用户订单 DTO、BUYER/SELLER、状态分组、订单号查询 | 现有分页和权限能力 |
| UI-02 | 筛选、分页、详情、日志、售后展示 | API-01、详情 DTO |
| API-02 | allowedActions、版本冲突与状态语义 | 主文档售后/支付状态规则 |
| UI-03 | 支付/取消/查看交付/确认/售后/评价操作 | 对应后端正确性修复及API-02 |
| UI-04 | 异步轮询、刷新恢复、超时查询、账号切换隔离 | 幂等命令/进度查询能力 |
| DATA-01 | 历史订单只读入口或迁移查询 | 历史数据盘点 |

第一阶段即可交付独立订单查询页面；完整功能开放以对应状态和操作安全条件为准。该顺序支持分批开发，但最终验收包括下述完整场景。

## 11. 验收清单

| 编号 | 场景 | 预期结果 |
| --- | --- | --- |
| OP-01 | 顶部导航、个人菜单进入 | 都到独立订单页；当前菜单高亮正确 |
| OP-02 | 未登录直达详情，再登录 | 返回原订单；无登录循环，外站returnTo无效 |
| OP-03 | 我买到的/我卖出的 | 身份隔离正确，不混用对方操作 |
| OP-04 | 状态筛选与计数 | 分组互斥、与分页总数一致，处理态不丢单 |
| OP-05 | 搜索、时间筛选、翻页、后退 | 条件/页码恢复，旧请求不能覆盖新条件 |
| OP-06 | 详情刷新与无权限订单号 | 正常重新查询；无权限不泄露数据 |
| OP-07 | 创建、支付、取消受理 | 展示处理中，查询终态后才提示完成 |
| OP-08 | 断网、超时、500 | 不虚构成功，不把错误显示为空订单，提供恢复入口 |
| OP-09 | 账号 A 请求未返回时切 B | A 的订单与敏感内容不出现在 B 页面 |
| OP-10 | 本地倒计时归零、时钟偏差 | 服务器状态决定是否可付；不擅自改订单状态 |
| OP-11 | 并发售后/确认/支付状态变化 | 409 后刷新并解释，不重复支付/交付 |
| OP-12 | 点击查看卡密、离开页面 | 显式披露、有审计；退出后内存/持久化缓存无明文 |
| OP-13 | 退款与迟到支付 | 准确显示处理中、完成金额和实际去向 |
| OP-14 | 旧快照/未知状态/下架商品 | 可展示订单历史，缺失信息有说明，无错误操作按钮 |
| OP-15 | 长列表与手机布局 | 使用真实分页，单页请求有界，键盘可操作，窄屏不遮挡主按钮 |
| OP-16 | 卖家/评价/售后操作 | 使用真实用户输入、服务器校验，不提交固定好评或固定售后原因 |

页面实施时应补充组件/接口契约验收，以及真实后端环境的购物→查单→支付→交付→确认/售后流程。本文仅定义方案，未运行页面或业务测试。
