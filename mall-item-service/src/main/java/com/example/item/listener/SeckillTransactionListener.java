package com.example.item.listener;

import com.example.item.mapper.ItemMapper;
import jakarta.annotation.Resource;
import org.apache.rocketmq.spring.annotation.RocketMQTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionState;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.Message;

@RocketMQTransactionListener
public class SeckillTransactionListener implements RocketMQLocalTransactionListener {

    @Resource
    private ItemMapper itemMapper;

    @Resource
    private JdbcTemplate jdbcTemplate;
    @Override
    public RocketMQLocalTransactionState executeLocalTransaction(Message msg, Object arg) {
        String transactionId = msg.getHeaders().get("TRANSACTION_ID", String.class);
        String orderNo = msg.getHeaders().get("ORDER_NO", String.class);
        Long itemId = Long.valueOf(msg.getHeaders().get("ITEM_ID", String.class));

        try {
            // 1. 扣减 MySQL 真实库存 (直接复用我们之前写好的 Try 阶段 SQL，或者写个新的)
            int updated = itemMapper.tryDeductStock(itemId, 1);
            if (updated <= 0) {
                throw new RuntimeException("MySQL 扣减库存失败");
            }

            // 2. 【简历核心亮点】插入本地事务日志表 (状态 1 代表成功)
            String sql = "INSERT INTO t_mq_transaction_log (transaction_id, order_no, status) VALUES (?, ?, 1)";
            jdbcTemplate.update(sql, transactionId, orderNo);

            System.out.println("本地事务执行成功，通知 MQ 提交消息！TX_ID: " + transactionId);
            return RocketMQLocalTransactionState.COMMIT; // 告诉 MQ：可以把消息发给订单服务了

        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("本地事务执行失败，通知 MQ 回滚消息！TX_ID: " + transactionId);
            return RocketMQLocalTransactionState.ROLLBACK;
        }
    }

    @Override
    public RocketMQLocalTransactionState checkLocalTransaction(Message msg) {
        String transactionId = msg.getHeaders().get("TRANSACTION_ID", String.class);
        System.out.println("触发 RocketMQ 事务状态回查，TX_ID: " + transactionId);

        // 查本地日志表
        String sql = "SELECT status FROM t_mq_transaction_log WHERE transaction_id = ?";
        try {
            Integer status = jdbcTemplate.queryForObject(sql, Integer.class, transactionId);
            if (status != null && status == 1) {
                return RocketMQLocalTransactionState.COMMIT;
            }
        } catch (Exception e) {
            // 查不到数据，说明本地事务没执行成功就回滚了
            return RocketMQLocalTransactionState.ROLLBACK;
        }
        return RocketMQLocalTransactionState.UNKNOWN;
    }
}
