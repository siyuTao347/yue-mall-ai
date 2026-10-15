package com.example.item.listener;

import com.example.item.mapper.ItemMapper;
import jakarta.annotation.Resource;
import org.apache.rocketmq.spring.annotation.RocketMQTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionState;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.Message;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@RocketMQTransactionListener
public class SeckillTransactionListener implements RocketMQLocalTransactionListener {

    @Resource
    private ItemMapper itemMapper;

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Resource
    private PlatformTransactionManager transactionManager;

    @Override
    public RocketMQLocalTransactionState executeLocalTransaction(Message msg, Object arg) {
        String transactionId = msg.getHeaders().get("TRANSACTION_ID", String.class);
        String orderNo = msg.getHeaders().get("ORDER_NO", String.class);
        Long itemId = Long.valueOf(msg.getHeaders().get("ITEM_ID", String.class));

        try {
            // SHOP-07: 扣减 MySQL 库存与写本地事务日志必须处于同一本地事务中，保证原子性！
            TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
            Boolean txSuccess = txTemplate.execute(status -> {
                int updated = itemMapper.tryDeductStock(itemId, 1);
                if (updated <= 0) {
                    throw new IllegalStateException("MySQL 扣减库存失败");
                }
                String sql = "INSERT INTO t_mq_transaction_log (transaction_id, order_no, status) VALUES (?, ?, 1)";
                jdbcTemplate.update(sql, transactionId, orderNo);
                return true;
            });

            if (Boolean.TRUE.equals(txSuccess)) {
                return RocketMQLocalTransactionState.COMMIT;
            } else {
                return RocketMQLocalTransactionState.ROLLBACK;
            }
        } catch (Exception e) {
            return RocketMQLocalTransactionState.ROLLBACK;
        }
    }

    @Override
    public RocketMQLocalTransactionState checkLocalTransaction(Message msg) {
        String transactionId = msg.getHeaders().get("TRANSACTION_ID", String.class);

        // 查本地日志表
        String sql = "SELECT status FROM t_mq_transaction_log WHERE transaction_id = ?";
        try {
            Integer status = jdbcTemplate.queryForObject(sql, Integer.class, transactionId);
            if (status != null && status == 1) {
                return RocketMQLocalTransactionState.COMMIT;
            }
            return RocketMQLocalTransactionState.ROLLBACK;
        } catch (EmptyResultDataAccessException e) {
            // 明确查不到日志，说明本地事务未成功
            return RocketMQLocalTransactionState.ROLLBACK;
        } catch (Exception e) {
            // 数据库连接异常或临时网络错误，不能贸然判定为回滚导致丢单，返回 UNKNOWN 等待重试回查
            return RocketMQLocalTransactionState.UNKNOWN;
        }
    }
}
