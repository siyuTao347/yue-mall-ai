package com.example.item.provider;


import api.ItemDubboService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.item.entity.TccActionLog;
import com.example.item.mapper.ItemMapper;
import com.example.item.mapper.TccActionLogMapper;
import io.seata.core.context.RootContext;
import io.seata.rm.tcc.api.BusinessActionContext;
import io.seata.rm.tcc.api.BusinessActionContextParameter;
import io.seata.rm.tcc.api.LocalTCC;
import io.seata.rm.tcc.api.TwoPhaseBusinessAction;
import jakarta.annotation.Resource;
import org.apache.dubbo.config.annotation.DubboService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


@Service
@DubboService
@LocalTCC
public class ItemDubboServiceImpl implements ItemDubboService {

    @Resource
    private ItemMapper itemMapper;
    @Resource
    private TccActionLogMapper tccActionLogMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    @TwoPhaseBusinessAction(name = "deductStockTccAction",
            commitMethod = "commitDeductStock",
            rollbackMethod = "rollbackDeductStock")
    public boolean prepareDeductStock(
            @BusinessActionContextParameter(paramName = "itemId") Long itemId,
            @BusinessActionContextParameter(paramName = "count") Integer count) {
        //获取全局 XID
        String xid = RootContext.getXID();

        // 防悬挂 幂等控制
        LambdaQueryWrapper<TccActionLog> query = new LambdaQueryWrapper<>();
        query.eq(TccActionLog::getTxId ,xid).eq(TccActionLog::getActionName,"deduct_stock");
        TccActionLog actionLog = tccActionLogMapper.selectOne(query);
        if(actionLog!=null){
            if(actionLog.getStatus()==4){
                throw  new RuntimeException("检测到防止悬挂记录，拒绝执行Try");
            }
            if(actionLog.getStatus()==1){
                return true;
            }
        }
        int updated = itemMapper.tryDeductStock(itemId, count);
        if(updated<=0){
            throw new RuntimeException("商品不存在或者库存不足");
        }

        TccActionLog newLog = new TccActionLog();
        newLog.setTxId(xid);
        newLog.setBranchId("UNKNOWN_IN_TRY");
        newLog.setStatus(1);
        newLog.setActionName("deduct_stock");
        tccActionLogMapper.insert(newLog);
        return true;
    }

    @Override
    public boolean commitDeductStock(BusinessActionContext context) {
        String xid = context.getXid();
        String branchId= String.valueOf(context.getBranchId());
        Long itemId = Long.valueOf(context.getActionContext("itemId").toString());
        Integer count = (Integer) context.getActionContext("count");

        //幂等校验
        LambdaQueryWrapper<TccActionLog> query = new LambdaQueryWrapper<>();
        query.eq(TccActionLog::getTxId, xid).eq(TccActionLog::getActionName, "deduct_stock");
        TccActionLog log = tccActionLogMapper.selectOne(query);

        if (log == null) return false; // 异常情况
        if (log.getStatus() == 2) return true; // 已经 Commit 过

        itemMapper.confirmDeductStock(itemId, count);

        // 3. 更新日志状态
        LambdaUpdateWrapper<TccActionLog> update = new LambdaUpdateWrapper<>();
        update.eq(TccActionLog::getTxId, xid)
                .set(TccActionLog::getStatus, 2)
                .set(TccActionLog::getBranchId, branchId);
        tccActionLogMapper.update(null, update);
        return true;
    }

    @Override
    public boolean rollbackDeductStock(BusinessActionContext context) {
        String xid = context.getXid();
        String branchId= String.valueOf(context.getBranchId());

        Object itemIdObj = context.getActionContext("itemId");
        Object countObj = context.getActionContext("count");

        // 如果上下文没有这两个参数，说明是之前的脏数据，直接返回 true 让 Seata 结束重试！
        if (itemIdObj == null || countObj == null) {
            System.err.println("拦截到无效的 Seata 脏事务重试，直接跳过。XID: " + xid);
            return true;
        }

        Long itemId = Long.valueOf(itemIdObj.toString());
        Integer count = (Integer) countObj;

        LambdaQueryWrapper<TccActionLog> query = new LambdaQueryWrapper<>();
        query.eq(TccActionLog::getTxId, xid).eq(TccActionLog::getActionName, "deduct_stock");
        TccActionLog log = tccActionLogMapper.selectOne(query);

        if (log == null) {
            // 说明 Try 根本没执行过，或者由于网络延迟 Try 还在路上！
            // 此时必须拦截此次回滚（防空回滚），并插入状态4的记录（防悬挂）
            TccActionLog dummyLog = new TccActionLog();
            dummyLog.setTxId(xid);
            dummyLog.setBranchId(branchId);
            dummyLog.setActionName("deduct_stock");
            dummyLog.setStatus(4); // 标记为防悬挂
            tccActionLogMapper.insert(dummyLog);
            return true;
        }

        if (log.getStatus() == 3 || log.getStatus() == 4) return true; // 幂等

        // 2. 核心 Cancel 逻辑：释放预留库存，归还可用库存
        itemMapper.cancelDeductStock(itemId, count);
        LambdaUpdateWrapper<TccActionLog> update = new LambdaUpdateWrapper<>();
        update.eq(TccActionLog::getTxId, xid)
                .set(TccActionLog::getStatus, 3)
                .set(TccActionLog::getBranchId, branchId);
        tccActionLogMapper.update(null, update);
        return true;
    }
}
