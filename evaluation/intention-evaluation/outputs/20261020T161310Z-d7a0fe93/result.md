
选定 200 条；有效分类 193 条；完整匹配 185 条
有效样本匹配率：95.85%；覆盖率：96.50%；匹配数/选定总数：92.50%
  CONTEXT_RESUME   4/4  100.00%
  ESCALATION_RULE  9/15  60.00%
  L1_RULE          14/14  100.00%
  L2_RAG           12/13  92.31%
  L3_LLM           146/147  99.32%
槽位匹配率：87.18%；Micro-F1：0.964286；Macro-F1：0.969356
状态分布：{"EVALUATED": 193, "ERROR": 7}
错误分组：{"422 AGENT_OUTPUT_INVALID: 重写问题包含无依据业务编号": 2, "422 AGENT_OUTPUT_INVALID: 业务编号没有原句或活跃实体依据：itemId": 2, "422 AGENT_OUTPUT_INVALID: 意图结构不正确": 2, "422 AGENT_OUTPUT_INVALID: 数据格式不正确": 1}
提示：存在跳过、异常或未执行；有效样本准确率不能代表完整选定集。
提示：本次仅测意图识别与槽位解析；上下文由显式夹具提供，不代表完整客服业务链路成绩。
报告目录：/Users/yu/IdeaProjects/mall-parent/evaluation/intention-evaluation/outputs/20261020T161310Z-d7a0fe93