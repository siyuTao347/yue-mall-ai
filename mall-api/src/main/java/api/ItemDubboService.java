package api;

import io.seata.rm.tcc.api.BusinessActionContext;
import io.seata.rm.tcc.api.BusinessActionContextParameter;
import io.seata.rm.tcc.api.LocalTCC;
import io.seata.rm.tcc.api.TwoPhaseBusinessAction;

//@LocalTCC //TCC 参与者
public interface ItemDubboService {

    /**
     * TCC - Try 阶段：预留/冻结库存
     */
    /*@TwoPhaseBusinessAction(name = "deductStockTccAction",
            commitMethod = "commitDeductStock",
            rollbackMethod = "rollbackDeductStock")
    boolean prepareDeductStock(
            @BusinessActionContextParameter(paramName = "itemId")Long itemId,
            @BusinessActionContextParameter(paramName = "count")Integer count);*/
    boolean prepareDeductStock(Long itemId, Integer count);
    /**
     * TCC - Confirm 阶段：真正扣减库存并解冻
     */
    boolean commitDeductStock(BusinessActionContext context);

    /**
     * TCC - Cancel 阶段：回滚预留库存（处理空回滚与悬挂）
     */
    boolean rollbackDeductStock(BusinessActionContext context);
}
