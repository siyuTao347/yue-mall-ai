-- L2 training expansion, batch CS-L2-20261020.
-- MySQL 8.0 / db_agent. New examples only; no embeddings are generated here.
-- Source: evaluation/intention-evaluation/l2_training_expansion.json
-- Adds 156 examples (13 intents x 12). With the default 26 examples: 182 total.
-- Uses each intent's current version. Does not change thresholds, definitions,
-- existing examples, release snapshots, PostgreSQL or the active release.
-- Rerunning the same file inserts zero additional rows.
-- Run this entire file in one connection; temporary tables are session-local.
-- If source_ready=0 or conflicting_existing_rows>0, no examples are inserted.
USE `db_agent`;
SET NAMES utf8mb4;

DROP TEMPORARY TABLE IF EXISTS `tmp_cs_l2_training_20261020`;
CREATE TEMPORARY TABLE `tmp_cs_l2_training_20261020` (
  `example_no` varchar(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
  `intent_code` varchar(48) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `example_text` varchar(512) NOT NULL,
  `normalized_text` varchar(512) NOT NULL,
  KEY `idx_intent_code` (`intent_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO `tmp_cs_l2_training_20261020`
  (`example_no`, `intent_code`, `example_text`, `normalized_text`)
VALUES
  ('CS-L2-20261020-ORDER_SEARCH-01', 'ORDER_SEARCH', '打开我的购买订单清单', '打开我的购买订单清单'),
  ('CS-L2-20261020-ORDER_SEARCH-02', 'ORDER_SEARCH', '把最近一个月我下过的单汇总给我', '把最近一个月我下过的单汇总给我'),
  ('CS-L2-20261020-ORDER_SEARCH-03', 'ORDER_SEARCH', '我想找回以前买卡密的交易记录', '我想找回以前买卡密的交易记录'),
  ('CS-L2-20261020-ORDER_SEARCH-04', 'ORDER_SEARCH', '查看我账号下所有待付款订单', '查看我账号下所有待付款订单'),
  ('CS-L2-20261020-ORDER_SEARCH-05', 'ORDER_SEARCH', '列出我卖出去的商品对应的订单', '列出我卖出去的商品对应的订单'),
  ('CS-L2-20261020-ORDER_SEARCH-06', 'ORDER_SEARCH', '能按下单日期给我的订单排个序吗', '能按下单日期给我的订单排个序吗'),
  ('CS-L2-20261020-ORDER_SEARCH-07', 'ORDER_SEARCH', '筛选出我还没有完成的那些交易', '筛选出我还没有完成的那些交易'),
  ('CS-L2-20261020-ORDER_SEARCH-08', 'ORDER_SEARCH', '我记不清订单编号了，先展示我的订单列表', '我记不清订单编号了,先展示我的订单列表'),
  ('CS-L2-20261020-ORDER_SEARCH-09', 'ORDER_SEARCH', '帮我找一下上周购买道具留下的订单', '帮我找一下上周购买道具留下的订单'),
  ('CS-L2-20261020-ORDER_SEARCH-10', 'ORDER_SEARCH', '我有哪些已经交易成功的订单', '我有哪些已经交易成功的订单'),
  ('CS-L2-20261020-ORDER_SEARCH-11', 'ORDER_SEARCH', '查询我最近取消过的订单清单', '查询我最近取消过的订单清单'),
  ('CS-L2-20261020-ORDER_SEARCH-12', 'ORDER_SEARCH', '展示我作为买家产生的交易订单', '展示我作为买家产生的交易订单'),
  ('CS-L2-20261020-ORDER_STATUS-01', 'ORDER_STATUS', '查查这张订单目前是待付款还是已付款', '查查这张订单目前是待付款还是已付款'),
  ('CS-L2-20261020-ORDER_STATUS-02', 'ORDER_STATUS', '我刚买的那单现在交易状态是什么', '我刚买的那单现在交易状态是什么'),
  ('CS-L2-20261020-ORDER_STATUS-03', 'ORDER_STATUS', '这笔购买的付款有没有被平台确认', '这笔购买的付款有没有被平台确认'),
  ('CS-L2-20261020-ORDER_STATUS-04', 'ORDER_STATUS', '卖家是否已经给这张订单发货了', '卖家是否已经给这张订单发货了'),
  ('CS-L2-20261020-ORDER_STATUS-05', 'ORDER_STATUS', '帮忙确认这单现在是否交易完成', '帮忙确认这单现在是否交易完成'),
  ('CS-L2-20261020-ORDER_STATUS-06', 'ORDER_STATUS', '我这笔订单当前还在等待交付吗', '我这笔订单当前还在等待交付吗'),
  ('CS-L2-20261020-ORDER_STATUS-07', 'ORDER_STATUS', '查一下指定订单是否已经取消', '查一下指定订单是否已经取消'),
  ('CS-L2-20261020-ORDER_STATUS-08', 'ORDER_STATUS', '这次买的卡密发到我的订单里了吗', '这次买的卡密发到我的订单里了吗'),
  ('CS-L2-20261020-ORDER_STATUS-09', 'ORDER_STATUS', '这单的当前页面状态帮我核实一下', '这单的当前页面状态帮我核实一下'),
  ('CS-L2-20261020-ORDER_STATUS-10', 'ORDER_STATUS', '我已经付款，这笔订单现在显示什么状态', '我已经付款,这笔订单现在显示什么状态'),
  ('CS-L2-20261020-ORDER_STATUS-11', 'ORDER_STATUS', '卖家说交付完了，看看我的订单是否已交付', '卖家说交付完了,看看我的订单是否已交付'),
  ('CS-L2-20261020-ORDER_STATUS-12', 'ORDER_STATUS', '请查这张订单现在的支付和交付情况', '请查这张订单现在的支付和交付情况'),
  ('CS-L2-20261020-ORDER_TIMELINE-01', 'ORDER_TIMELINE', '列出这张订单的历史状态变更记录', '列出这张订单的历史状态变更记录'),
  ('CS-L2-20261020-ORDER_TIMELINE-02', 'ORDER_TIMELINE', '我想核对这笔交易创建、支付、交付各自的时间', '我想核对这笔交易创建、支付、交付各自的时间'),
  ('CS-L2-20261020-ORDER_TIMELINE-03', 'ORDER_TIMELINE', '这张订单具体是哪一刻完成支付的', '这张订单具体是哪一刻完成支付的'),
  ('CS-L2-20261020-ORDER_TIMELINE-04', 'ORDER_TIMELINE', '把这个订单经历过的流程节点按时间排出来', '把这个订单经历过的流程节点按时间排出来'),
  ('CS-L2-20261020-ORDER_TIMELINE-05', 'ORDER_TIMELINE', '查询卖家为这张订单发货时的时间戳', '查询卖家为这张订单发货时的时间戳'),
  ('CS-L2-20261020-ORDER_TIMELINE-06', 'ORDER_TIMELINE', '我要查看这笔交易从创建到完成的全过程记录', '我要查看这笔交易从创建到完成的全过程记录'),
  ('CS-L2-20261020-ORDER_TIMELINE-07', 'ORDER_TIMELINE', '订单进入托管的时间和交付的时间分别是什么', '订单进入托管的时间和交付的时间分别是什么'),
  ('CS-L2-20261020-ORDER_TIMELINE-08', 'ORDER_TIMELINE', '查查这张订单之前都变更过哪些状态', '查查这张订单之前都变更过哪些状态'),
  ('CS-L2-20261020-ORDER_TIMELINE-09', 'ORDER_TIMELINE', '我需要这笔交易每一步操作的发生时间', '我需要这笔交易每一步操作的发生时间'),
  ('CS-L2-20261020-ORDER_TIMELINE-10', 'ORDER_TIMELINE', '这单是什么时候由待支付变成已支付的', '这单是什么时候由待支付变成已支付的'),
  ('CS-L2-20261020-ORDER_TIMELINE-11', 'ORDER_TIMELINE', '展示这个订单的历史时间轴', '展示这个订单的历史时间轴'),
  ('CS-L2-20261020-ORDER_TIMELINE-12', 'ORDER_TIMELINE', '请提供这笔订单付款到交付之间的节点记录', '请提供这笔订单付款到交付之间的节点记录'),
  ('CS-L2-20261020-SETTLEMENT-01', 'SETTLEMENT', '这张已完成订单的卖家货款结算了吗', '这张已完成订单的卖家货款结算了吗'),
  ('CS-L2-20261020-SETTLEMENT-02', 'SETTLEMENT', '帮我查询本单结算款是否已经释放', '帮我查询本单结算款是否已经释放'),
  ('CS-L2-20261020-SETTLEMENT-03', 'SETTLEMENT', '我这笔交易的卖家收益现在还冻结着吗', '我这笔交易的卖家收益现在还冻结着吗'),
  ('CS-L2-20261020-SETTLEMENT-04', 'SETTLEMENT', '这单货款什么时候转入我的平台余额', '这单货款什么时候转入我的平台余额'),
  ('CS-L2-20261020-SETTLEMENT-05', 'SETTLEMENT', '查看这张订单结算流程的当前进度', '查看这张订单结算流程的当前进度'),
  ('CS-L2-20261020-SETTLEMENT-06', 'SETTLEMENT', '本笔交易已经交付，结算状态帮我查一下', '本笔交易已经交付,结算状态帮我查一下'),
  ('CS-L2-20261020-SETTLEMENT-07', 'SETTLEMENT', '我卖出的这个订单还有多久解除结算冻结', '我卖出的这个订单还有多久解除结算冻结'),
  ('CS-L2-20261020-SETTLEMENT-08', 'SETTLEMENT', '核实这笔订单的货款有没有结算给我', '核实这笔订单的货款有没有结算给我'),
  ('CS-L2-20261020-SETTLEMENT-09', 'SETTLEMENT', '查查这张订单预计的结算到账日期', '查查这张订单预计的结算到账日期'),
  ('CS-L2-20261020-SETTLEMENT-10', 'SETTLEMENT', '这笔买卖对应的收入为什么还显示待结算', '这笔买卖对应的收入为什么还显示待结算'),
  ('CS-L2-20261020-SETTLEMENT-11', 'SETTLEMENT', '我这张卖家订单现在是否具备放款状态', '我这张卖家订单现在是否具备放款状态'),
  ('CS-L2-20261020-SETTLEMENT-12', 'SETTLEMENT', '本单的托管货款已经进入我的可用余额了吗', '本单的托管货款已经进入我的可用余额了吗'),
  ('CS-L2-20261020-AFTER_SALE_PROGRESS-01', 'AFTER_SALE_PROGRESS', '查询我已经登记的售后工单当前进度', '查询我已经登记的售后工单当前进度'),
  ('CS-L2-20261020-AFTER_SALE_PROGRESS-02', 'AFTER_SALE_PROGRESS', '昨天提交的纠纷申请现在审核完了吗', '昨天提交的纠纷申请现在审核完了吗'),
  ('CS-L2-20261020-AFTER_SALE_PROGRESS-03', 'AFTER_SALE_PROGRESS', '这张退款售后单目前由谁处理到哪一步', '这张退款售后单目前由谁处理到哪一步'),
  ('CS-L2-20261020-AFTER_SALE_PROGRESS-04', 'AFTER_SALE_PROGRESS', '我补过证据的那次售后现在有结果了吗', '我补过证据的那次售后现在有结果了吗'),
  ('CS-L2-20261020-AFTER_SALE_PROGRESS-05', 'AFTER_SALE_PROGRESS', '已经申请的售后是否还在等待卖家回应', '已经申请的售后是否还在等待卖家回应'),
  ('CS-L2-20261020-AFTER_SALE_PROGRESS-06', 'AFTER_SALE_PROGRESS', '看一下这张争议处理单的当前状态', '看一下这张争议处理单的当前状态'),
  ('CS-L2-20261020-AFTER_SALE_PROGRESS-07', 'AFTER_SALE_PROGRESS', '我那份售后申请现在是受理中还是已结束', '我那份售后申请现在是受理中还是已结束'),
  ('CS-L2-20261020-AFTER_SALE_PROGRESS-08', 'AFTER_SALE_PROGRESS', '之前发起的纠纷单是否已经有处理结论', '之前发起的纠纷单是否已经有处理结论'),
  ('CS-L2-20261020-AFTER_SALE_PROGRESS-09', 'AFTER_SALE_PROGRESS', '查查我提交的退款工单审核进度', '查查我提交的退款工单审核进度'),
  ('CS-L2-20261020-AFTER_SALE_PROGRESS-10', 'AFTER_SALE_PROGRESS', '那张正在处理的售后单最近有什么进展', '那张正在处理的售后单最近有什么进展'),
  ('CS-L2-20261020-AFTER_SALE_PROGRESS-11', 'AFTER_SALE_PROGRESS', '平台是否已经受理我的这份售后申请', '平台是否已经受理我的这份售后申请'),
  ('CS-L2-20261020-AFTER_SALE_PROGRESS-12', 'AFTER_SALE_PROGRESS', '帮忙确认这张纠纷单当前有没有结案', '帮忙确认这张纠纷单当前有没有结案'),
  ('CS-L2-20261020-AFTER_SALE_GUIDE-01', 'AFTER_SALE_GUIDE', '购买的道具与描述不一致，要怎样发起售后', '购买的道具与描述不一致,要怎样发起售后'),
  ('CS-L2-20261020-AFTER_SALE_GUIDE-02', 'AFTER_SALE_GUIDE', '卡密兑换失败时可以通过什么步骤申请退款', '卡密兑换失败时可以通过什么步骤申请退款'),
  ('CS-L2-20261020-AFTER_SALE_GUIDE-03', 'AFTER_SALE_GUIDE', '向平台提交纠纷申请需要哪些证据', '向平台提交纠纷申请需要哪些证据'),
  ('CS-L2-20261020-AFTER_SALE_GUIDE-04', 'AFTER_SALE_GUIDE', '申请售后时应该上传哪些截图和说明', '申请售后时应该上传哪些截图和说明'),
  ('CS-L2-20261020-AFTER_SALE_GUIDE-05', 'AFTER_SALE_GUIDE', '交易完成后还能在什么条件下申请售后', '交易完成后还能在什么条件下申请售后'),
  ('CS-L2-20261020-AFTER_SALE_GUIDE-06', 'AFTER_SALE_GUIDE', '我还没有开售后单，告诉我在哪里提交申请', '我还没有开售后单,告诉我在哪里提交申请'),
  ('CS-L2-20261020-AFTER_SALE_GUIDE-07', 'AFTER_SALE_GUIDE', '卖家交付的内容有问题，我该如何举证', '卖家交付的内容有问题,我该如何举证'),
  ('CS-L2-20261020-AFTER_SALE_GUIDE-08', 'AFTER_SALE_GUIDE', '售后申请有哪些时间限制和材料要求', '售后申请有哪些时间限制和材料要求'),
  ('CS-L2-20261020-AFTER_SALE_GUIDE-09', 'AFTER_SALE_GUIDE', '买到无法使用的虚拟商品可以怎么申诉', '买到无法使用的虚拟商品可以怎么申诉'),
  ('CS-L2-20261020-AFTER_SALE_GUIDE-10', 'AFTER_SALE_GUIDE', '请说明买家申请退款的完整操作流程', '请说明买家申请退款的完整操作流程'),
  ('CS-L2-20261020-AFTER_SALE_GUIDE-11', 'AFTER_SALE_GUIDE', '售后证据不齐时通常需要补充哪些资料', '售后证据不齐时通常需要补充哪些资料'),
  ('CS-L2-20261020-AFTER_SALE_GUIDE-12', 'AFTER_SALE_GUIDE', '收到错误的卡密后如何向平台申请处理', '收到错误的卡密后如何向平台申请处理'),
  ('CS-L2-20261020-AFTER_SALE_STATUS_COMBINED-01', 'AFTER_SALE_STATUS_COMBINED', '这张订单和由它发起的售后工单都查一下当前状态', '这张订单和由它发起的售后工单都查一下当前状态'),
  ('CS-L2-20261020-AFTER_SALE_STATUS_COMBINED-02', 'AFTER_SALE_STATUS_COMBINED', '我需要同一笔交易的订单进度及对应纠纷进度', '我需要同一笔交易的订单进度及对应纠纷进度'),
  ('CS-L2-20261020-AFTER_SALE_STATUS_COMBINED-03', 'AFTER_SALE_STATUS_COMBINED', '把这单的交易状态与关联退款申请的状态一起给我', '把这单的交易状态与关联退款申请的状态一起给我'),
  ('CS-L2-20261020-AFTER_SALE_STATUS_COMBINED-04', 'AFTER_SALE_STATUS_COMBINED', '同时核实原订单有没有完成和它的售后有没有结案', '同时核实原订单有没有完成和它的售后有没有结案'),
  ('CS-L2-20261020-AFTER_SALE_STATUS_COMBINED-05', 'AFTER_SALE_STATUS_COMBINED', '请联合查询这笔购买记录及其关联售后记录的处理情况', '请联合查询这笔购买记录及其关联售后记录的处理情况'),
  ('CS-L2-20261020-AFTER_SALE_STATUS_COMBINED-06', 'AFTER_SALE_STATUS_COMBINED', '这张订单还在交付吗，它对应的售后是否已受理', '这张订单还在交付吗,它对应的售后是否已受理'),
  ('CS-L2-20261020-AFTER_SALE_STATUS_COMBINED-07', 'AFTER_SALE_STATUS_COMBINED', '我要看这单本身的状态，也要看这单退款工单的进展', '我要看这单本身的状态,也要看这单退款工单的进展'),
  ('CS-L2-20261020-AFTER_SALE_STATUS_COMBINED-08', 'AFTER_SALE_STATUS_COMBINED', '原交易订单和为这笔交易提交的纠纷单现在各是什么情况', '原交易订单和为这笔交易提交的纠纷单现在各是什么情况'),
  ('CS-L2-20261020-AFTER_SALE_STATUS_COMBINED-09', 'AFTER_SALE_STATUS_COMBINED', '请把这笔订单以及由这笔订单产生的售后状态一并核对', '请把这笔订单以及由这笔订单产生的售后状态一并核对'),
  ('CS-L2-20261020-AFTER_SALE_STATUS_COMBINED-10', 'AFTER_SALE_STATUS_COMBINED', '查一下本订单的交付进度和对应售后申请的处理进度', '查一下本订单的交付进度和对应售后申请的处理进度'),
  ('CS-L2-20261020-AFTER_SALE_STATUS_COMBINED-11', 'AFTER_SALE_STATUS_COMBINED', '同一订单的交易记录和售后工单分别处于哪个状态', '同一订单的交易记录和售后工单分别处于哪个状态'),
  ('CS-L2-20261020-AFTER_SALE_STATUS_COMBINED-12', 'AFTER_SALE_STATUS_COMBINED', '这笔交易的订单与关联争议单当前是否都已经结束', '这笔交易的订单与关联争议单当前是否都已经结束'),
  ('CS-L2-20261020-PLATFORM_RULE-01', 'PLATFORM_RULE', '平台允许交易哪些类别的虚拟资产', '平台允许交易哪些类别的虚拟资产'),
  ('CS-L2-20261020-PLATFORM_RULE-02', 'PLATFORM_RULE', '在这里出售违禁商品有什么平台规定', '在这里出售违禁商品有什么平台规定'),
  ('CS-L2-20261020-PLATFORM_RULE-03', 'PLATFORM_RULE', '担保交易中买家和卖家各需要遵守什么规则', '担保交易中买家和卖家各需要遵守什么规则'),
  ('CS-L2-20261020-PLATFORM_RULE-04', 'PLATFORM_RULE', '平台会在满足什么条件时自动确认收货', '平台会在满足什么条件时自动确认收货'),
  ('CS-L2-20261020-PLATFORM_RULE-05', 'PLATFORM_RULE', '私下绕开平台成交是否违反交易规范', '私下绕开平台成交是否违反交易规范'),
  ('CS-L2-20261020-PLATFORM_RULE-06', 'PLATFORM_RULE', '发布虚拟商品有哪些禁止事项', '发布虚拟商品有哪些禁止事项'),
  ('CS-L2-20261020-PLATFORM_RULE-07', 'PLATFORM_RULE', '请介绍平台对交易双方的基本保障制度', '请介绍平台对交易双方的基本保障制度'),
  ('CS-L2-20261020-PLATFORM_RULE-08', 'PLATFORM_RULE', '订单交付以后一般多久会自动确认完成', '订单交付以后一般多久会自动确认完成'),
  ('CS-L2-20261020-PLATFORM_RULE-09', 'PLATFORM_RULE', '哪些商品类型会被平台列入禁售范围', '哪些商品类型会被平台列入禁售范围'),
  ('CS-L2-20261020-PLATFORM_RULE-10', 'PLATFORM_RULE', '卖家要求站外付款是否符合平台交易规则', '卖家要求站外付款是否符合平台交易规则'),
  ('CS-L2-20261020-PLATFORM_RULE-11', 'PLATFORM_RULE', '担保付款和直接转账在平台规则上有什么区别', '担保付款和直接转账在平台规则上有什么区别'),
  ('CS-L2-20261020-PLATFORM_RULE-12', 'PLATFORM_RULE', '平台对虚拟商品描述真实性有什么要求', '平台对虚拟商品描述真实性有什么要求'),
  ('CS-L2-20261020-SETTLEMENT_RULE-01', 'SETTLEMENT_RULE', '卖家订单完成后一般还要等几个结算日', '卖家订单完成后一般还要等几个结算日'),
  ('CS-L2-20261020-SETTLEMENT_RULE-02', 'SETTLEMENT_RULE', '平台对卖家货款释放设置了怎样的冷却期', '平台对卖家货款释放设置了怎样的冷却期'),
  ('CS-L2-20261020-SETTLEMENT_RULE-03', 'SETTLEMENT_RULE', '请说明普通交易完成后的结算制度', '请说明普通交易完成后的结算制度'),
  ('CS-L2-20261020-SETTLEMENT_RULE-04', 'SETTLEMENT_RULE', '卖家收益通常按照什么周期结算', '卖家收益通常按照什么周期结算'),
  ('CS-L2-20261020-SETTLEMENT_RULE-05', 'SETTLEMENT_RULE', '交易的担保资金满足哪些条件才会释放', '交易的担保资金满足哪些条件才会释放'),
  ('CS-L2-20261020-SETTLEMENT_RULE-06', 'SETTLEMENT_RULE', '订单完成和卖家拿到结算款之间有等待期吗', '订单完成和卖家拿到结算款之间有等待期吗'),
  ('CS-L2-20261020-SETTLEMENT_RULE-07', 'SETTLEMENT_RULE', '平台一般如何计算卖家结算的起始日期', '平台一般如何计算卖家结算的起始日期'),
  ('CS-L2-20261020-SETTLEMENT_RULE-08', 'SETTLEMENT_RULE', '节假日会怎样影响平台的货款结算周期', '节假日会怎样影响平台的货款结算周期'),
  ('CS-L2-20261020-SETTLEMENT_RULE-09', 'SETTLEMENT_RULE', '卖家正常结算需要达到哪些通用条件', '卖家正常结算需要达到哪些通用条件'),
  ('CS-L2-20261020-SETTLEMENT_RULE-10', 'SETTLEMENT_RULE', '有售后争议时平台的一般结算规则是什么', '有售后争议时平台的一般结算规则是什么'),
  ('CS-L2-20261020-SETTLEMENT_RULE-11', 'SETTLEMENT_RULE', '请介绍担保交易结束后的货款结算安排', '请介绍担保交易结束后的货款结算安排'),
  ('CS-L2-20261020-SETTLEMENT_RULE-12', 'SETTLEMENT_RULE', '平台规定的卖家结算冷却时间怎么算', '平台规定的卖家结算冷却时间怎么算'),
  ('CS-L2-20261020-WITHDRAW_RULE-01', 'WITHDRAW_RULE', '账户里的可用余额转出到银行卡有什么要求', '账户里的可用余额转出到银行卡有什么要求'),
  ('CS-L2-20261020-WITHDRAW_RULE-02', 'WITHDRAW_RULE', '平台余额至少达到多少才能申请提现', '平台余额至少达到多少才能申请提现'),
  ('CS-L2-20261020-WITHDRAW_RULE-03', 'WITHDRAW_RULE', '每天从平台取出余额的金额有没有上限', '每天从平台取出余额的金额有没有上限'),
  ('CS-L2-20261020-WITHDRAW_RULE-04', 'WITHDRAW_RULE', '把卖货所得从账户余额提到银行卡需要审核吗', '把卖货所得从账户余额提到银行卡需要审核吗'),
  ('CS-L2-20261020-WITHDRAW_RULE-05', 'WITHDRAW_RULE', '提现的银行卡持有人必须和实名信息一致吗', '提现的银行卡持有人必须和实名信息一致吗'),
  ('CS-L2-20261020-WITHDRAW_RULE-06', 'WITHDRAW_RULE', '平台余额提现通常需要经过哪些审核步骤', '平台余额提现通常需要经过哪些审核步骤'),
  ('CS-L2-20261020-WITHDRAW_RULE-07', 'WITHDRAW_RULE', '申请余额提现后一般需要几个工作日到账', '申请余额提现后一般需要几个工作日到账'),
  ('CS-L2-20261020-WITHDRAW_RULE-08', 'WITHDRAW_RULE', '可提现余额不足最低金额时能否发起提现', '可提现余额不足最低金额时能否发起提现'),
  ('CS-L2-20261020-WITHDRAW_RULE-09', 'WITHDRAW_RULE', '一天内可以提交几次余额提现申请', '一天内可以提交几次余额提现申请'),
  ('CS-L2-20261020-WITHDRAW_RULE-10', 'WITHDRAW_RULE', '我想把账户里的钱转到银行卡，平台有哪些限制', '我想把账户里的钱转到银行卡,平台有哪些限制'),
  ('CS-L2-20261020-WITHDRAW_RULE-11', 'WITHDRAW_RULE', '未完成实名认证可以把平台余额提现吗', '未完成实名认证可以把平台余额提现吗'),
  ('CS-L2-20261020-WITHDRAW_RULE-12', 'WITHDRAW_RULE', '银行卡收取平台提现款前需要满足什么条件', '银行卡收取平台提现款前需要满足什么条件'),
  ('CS-L2-20261020-ACCOUNT_RULE-01', 'ACCOUNT_RULE', '注册账号后怎样完成平台的实名验证', '注册账号后怎样完成平台的实名验证'),
  ('CS-L2-20261020-ACCOUNT_RULE-02', 'ACCOUNT_RULE', '绑定新手机号时平台有什么验证要求', '绑定新手机号时平台有什么验证要求'),
  ('CS-L2-20261020-ACCOUNT_RULE-03', 'ACCOUNT_RULE', '平台账号修改密码需要经过哪些校验', '平台账号修改密码需要经过哪些校验'),
  ('CS-L2-20261020-ACCOUNT_RULE-04', 'ACCOUNT_RULE', '开通商家身份要满足什么账号认证条件', '开通商家身份要满足什么账号认证条件'),
  ('CS-L2-20261020-ACCOUNT_RULE-05', 'ACCOUNT_RULE', '商家入驻保证金的通用规则是什么', '商家入驻保证金的通用规则是什么'),
  ('CS-L2-20261020-ACCOUNT_RULE-06', 'ACCOUNT_RULE', '平台在什么情况下会限制账号交易权限', '平台在什么情况下会限制账号交易权限'),
  ('CS-L2-20261020-ACCOUNT_RULE-07', 'ACCOUNT_RULE', '请说明账号被风控冻结后的申诉规则', '请说明账号被风控冻结后的申诉规则'),
  ('CS-L2-20261020-ACCOUNT_RULE-08', 'ACCOUNT_RULE', '实名认证的证件信息有哪些填写要求', '实名认证的证件信息有哪些填写要求'),
  ('CS-L2-20261020-ACCOUNT_RULE-09', 'ACCOUNT_RULE', '更换账号绑定邮箱需要走什么验证流程', '更换账号绑定邮箱需要走什么验证流程'),
  ('CS-L2-20261020-ACCOUNT_RULE-10', 'ACCOUNT_RULE', '一个实名身份能否绑定多个平台账号', '一个实名身份能否绑定多个平台账号'),
  ('CS-L2-20261020-ACCOUNT_RULE-11', 'ACCOUNT_RULE', '保护平台账户安全有哪些认证设置', '保护平台账户安全有哪些认证设置'),
  ('CS-L2-20261020-ACCOUNT_RULE-12', 'ACCOUNT_RULE', '商家关闭店铺时保证金按什么规定退还', '商家关闭店铺时保证金按什么规定退还'),
  ('CS-L2-20261020-COMPLAINT-01', 'COMPLAINT', '我对卖家的服务不满意，要登记投诉', '我对卖家的服务不满意,要登记投诉'),
  ('CS-L2-20261020-COMPLAINT-02', 'COMPLAINT', '商家一直用恶劣态度回复，我想举报这种行为', '商家一直用恶劣态度回复,我想举报这种行为'),
  ('CS-L2-20261020-COMPLAINT-03', 'COMPLAINT', '我要对这家店铺提出正式的服务投诉', '我要对这家店铺提出正式的服务投诉'),
  ('CS-L2-20261020-COMPLAINT-04', 'COMPLAINT', '平台处理问题的态度让我不满，我要投诉', '平台处理问题的态度让我不满,我要投诉'),
  ('CS-L2-20261020-COMPLAINT-05', 'COMPLAINT', '卖家的商品描述误导消费者，我要举报', '卖家的商品描述误导消费者,我要举报'),
  ('CS-L2-20261020-COMPLAINT-06', 'COMPLAINT', '这位商家反复辱骂买家，请记录我的投诉', '这位商家反复辱骂买家,请记录我的投诉'),
  ('CS-L2-20261020-COMPLAINT-07', 'COMPLAINT', '我要反映一个卖家长期骚扰买家的问题', '我要反映一个卖家长期骚扰买家的问题'),
  ('CS-L2-20261020-COMPLAINT-08', 'COMPLAINT', '平台工作人员的处理方式不合理，我提出投诉', '平台工作人员的处理方式不合理,我提出投诉'),
  ('CS-L2-20261020-COMPLAINT-09', 'COMPLAINT', '这家店铺发布虚假宣传，我要向平台举报', '这家店铺发布虚假宣传,我要向平台举报'),
  ('CS-L2-20261020-COMPLAINT-10', 'COMPLAINT', '我需要投诉商家承诺与实际服务不一致', '我需要投诉商家承诺与实际服务不一致'),
  ('CS-L2-20261020-COMPLAINT-11', 'COMPLAINT', '卖家对合理咨询一直敷衍，请受理我的投诉', '卖家对合理咨询一直敷衍,请受理我的投诉'),
  ('CS-L2-20261020-COMPLAINT-12', 'COMPLAINT', '我要举报这位卖家存在违反平台规范的行为', '我要举报这位卖家存在违反平台规范的行为'),
  ('CS-L2-20261020-HUMAN_HELP-01', 'HUMAN_HELP', '我要和人工坐席直接沟通', '我要和人工坐席直接沟通'),
  ('CS-L2-20261020-HUMAN_HELP-02', 'HUMAN_HELP', '麻烦把当前会话交给真人客服', '麻烦把当前会话交给真人客服'),
  ('CS-L2-20261020-HUMAN_HELP-03', 'HUMAN_HELP', '帮我接通一个人工客服人员', '帮我接通一个人工客服人员'),
  ('CS-L2-20261020-HUMAN_HELP-04', 'HUMAN_HELP', '请安排客服专员来接待这次咨询', '请安排客服专员来接待这次咨询'),
  ('CS-L2-20261020-HUMAN_HELP-05', 'HUMAN_HELP', '我希望由人工继续处理这个问题', '我希望由人工继续处理这个问题'),
  ('CS-L2-20261020-HUMAN_HELP-06', 'HUMAN_HELP', '现在能否转接到真人服务', '现在能否转接到真人服务'),
  ('CS-L2-20261020-HUMAN_HELP-07', 'HUMAN_HELP', '我要找平台的人工工作人员聊一下', '我要找平台的人工工作人员聊一下'),
  ('CS-L2-20261020-HUMAN_HELP-08', 'HUMAN_HELP', '请让我进入人工客服接待队列', '请让我进入人工客服接待队列'),
  ('CS-L2-20261020-HUMAN_HELP-09', 'HUMAN_HELP', '这个问题我想直接向人工坐席说明', '这个问题我想直接向人工坐席说明'),
  ('CS-L2-20261020-HUMAN_HELP-10', 'HUMAN_HELP', '接下来请真人客服与我联系', '接下来请真人客服与我联系'),
  ('CS-L2-20261020-HUMAN_HELP-11', 'HUMAN_HELP', '帮我把这次咨询转交给人工专员', '帮我把这次咨询转交给人工专员'),
  ('CS-L2-20261020-HUMAN_HELP-12', 'HUMAN_HELP', '请接通人工服务，我愿意等候', '请接通人工服务,我愿意等候');

START TRANSACTION;

-- Lock the source definitions while checking and inserting this batch.
SELECT i.intent_code, i.intent_version, i.status
FROM t_customer_service_intent i
JOIN (SELECT DISTINCT intent_code FROM tmp_cs_l2_training_20261020) p
  ON p.intent_code = i.intent_code
FOR UPDATE;

SET @cs_l2_source_ready_20261020 := (
  SELECT COUNT(*) = 13
  FROM t_customer_service_intent i
  JOIN (SELECT DISTINCT intent_code FROM tmp_cs_l2_training_20261020) p
    ON p.intent_code = i.intent_code
  WHERE i.status = 'ENABLED' AND i.intent_version > 0
);

-- Existing IDs must match this exact batch and the current source version.
-- If someone has edited/disabled these examples, stop instead of overwriting.
SET @cs_l2_conflicts_20261020 := (
  SELECT COUNT(*)
  FROM tmp_cs_l2_training_20261020 p
  JOIN t_customer_service_intent i ON i.intent_code = p.intent_code
  JOIN t_customer_service_intent_example e ON e.example_no = p.example_no
  WHERE e.example_version <> 1
     OR e.intent_code <> p.intent_code
     OR e.intent_version <> i.intent_version
     OR e.status <> 'ENABLED'
     OR BINARY e.example_text <> BINARY p.example_text
     OR BINARY e.normalized_text <> BINARY p.normalized_text
     OR e.example_hash <> SHA2(p.normalized_text, 256)
);

INSERT INTO t_customer_service_intent_example
  (example_no, example_version, intent_code, intent_version,
   example_text, normalized_text, example_hash, status, embedding_status)
SELECT p.example_no, 1, p.intent_code, i.intent_version,
       p.example_text, p.normalized_text, SHA2(p.normalized_text, 256),
       'ENABLED', 'PENDING'
FROM tmp_cs_l2_training_20261020 p
JOIN t_customer_service_intent i ON i.intent_code = p.intent_code
LEFT JOIN t_customer_service_intent_example e ON e.example_no = p.example_no
WHERE @cs_l2_source_ready_20261020 = 1
  AND @cs_l2_conflicts_20261020 = 0
  AND e.example_no IS NULL;

SET @cs_l2_inserted_20261020 := ROW_COUNT();
COMMIT;

-- First application: 1, 0, 156. Exact rerun: 1, 0, 0.
SELECT @cs_l2_source_ready_20261020 AS source_ready,
       @cs_l2_conflicts_20261020 AS conflicting_existing_rows,
       @cs_l2_inserted_20261020 AS inserted_examples;

-- Must be 156 before creating a new release.
SELECT COUNT(*) AS matching_batch_examples
FROM tmp_cs_l2_training_20261020 p
JOIN t_customer_service_intent i ON i.intent_code = p.intent_code
JOIN t_customer_service_intent_example e ON e.example_no = p.example_no
WHERE e.example_version = 1 AND e.intent_code = p.intent_code
  AND e.intent_version = i.intent_version AND e.status = 'ENABLED'
  AND BINARY e.example_text = BINARY p.example_text
  AND BINARY e.normalized_text = BINARY p.normalized_text
  AND e.example_hash = SHA2(p.normalized_text, 256);

-- With the unchanged default seed: each of these 13 intents has 14 examples.
SELECT i.intent_code, i.intent_version,
       COUNT(e.example_no) AS enabled_examples,
       COALESCE(SUM(e.intent_version <> i.intent_version), 0) AS stale_version_examples
FROM t_customer_service_intent i
JOIN (SELECT DISTINCT intent_code FROM tmp_cs_l2_training_20261020) p
  ON p.intent_code = i.intent_code
LEFT JOIN t_customer_service_intent_example e
  ON e.intent_code = i.intent_code AND e.status = 'ENABLED'
GROUP BY i.intent_code, i.intent_version
ORDER BY i.intent_code;

DROP TEMPORARY TABLE tmp_cs_l2_training_20261020;
