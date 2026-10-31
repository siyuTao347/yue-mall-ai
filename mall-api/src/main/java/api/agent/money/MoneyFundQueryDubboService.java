package api.agent.money;

/**
 * 平台资金账本受限只读查询服务 (由 User 服务提供)
 */
public interface MoneyFundQueryDubboService {

    /**
     * 根据已验证的范围凭据获取关联的资金事务与流水
     */
    MoneyFundSnapshot getSnapshot(MoneyAccessContext access, MoneyFundQuery query);
}

