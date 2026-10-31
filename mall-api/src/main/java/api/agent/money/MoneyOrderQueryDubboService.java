package api.agent.money;

/**
 * 订单与支付事实受限只读查询服务 (由 Order 服务提供)
 */
public interface MoneyOrderQueryDubboService {

    /**
     * 权威解析调查目标（校验用户归属、收集关联 attempts/refunds 并签发 scopeProof）
     */
    ResolveMoneySubjectResult resolveSubject(MoneyAccessContext access, MoneySubjectSelector selector);

    /**
     * 获取订单与支付事实快照
     */
    MoneyOrderSnapshot getSnapshot(MoneyAccessContext access, MoneySnapshotQuery query);

    /**
     * 只读观察支付渠道实时状态（严禁写业务表，仅写观察审计）
     */
    MoneyChannelObservation observeChannel(MoneyAccessContext access, MoneyChannelQuery query);

    /**
     * 获取对账差异原始条目与窗口闭合回溯证据
     */
    MoneyReconEvidence getReconEvidence(MoneyAccessContext access, MoneyReconQuery query);
}

