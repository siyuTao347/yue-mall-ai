package com.example.item.component;

import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import jakarta.annotation.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

@Component
public class ReconciliationJobHandler {

    @Resource
    private JdbcTemplate jdbcTemplate;

    @XxlJob("wechatReconciliationJob")
    public void wechatReconciliationJob() throws Exception {
        XxlJobHelper.log("====== 开始执行 T+1 微信支付对账任务 ======");
        long startTime = System.currentTimeMillis();

        // 1. 从真实数据库拉取平台昨日所有已支付订单 (模拟 10 万条数据拉取)
        XxlJobHelper.log("1. 开始从数据库拉取平台订单...");
        Map<String, BigDecimal> platformBill = new HashMap<>();
        // 真实查询！把之前 Python 脚本造的 10 万条数据全部拉进内存
        String sql = "SELECT order_no, pay_amount FROM t_order WHERE status = 1";
        jdbcTemplate.query(sql, rs -> {
            platformBill.put(rs.getString("order_no"), rs.getBigDecimal("pay_amount"));
        });
        XxlJobHelper.log("平台订单拉取成功，总数: " + platformBill.size());

        // 2. 模拟解析微信官方对账单 (基于真实数据生成微信账单，故意激活毒数据)
        XxlJobHelper.log("2. 正在下载并解析微信渠道账单...");
        Map<String, BigDecimal> wechatBill = new HashMap<>();
        for (String orderNo : platformBill.keySet()) {
            if (orderNo.startsWith("ERR_ORD")) {
                // 【激活毒数据】：如果是 Python 写的异常单，我们模拟一半是掉单，一半是金额不对
                if (Math.random() > 0.5) {
                    wechatBill.put(orderNo, new BigDecimal("99.00")); // 微信是99，平台是瞎写的金额，触发“金额不一致”
                }
                // 另外一半不 put，模拟微信没收到钱，但平台显示支付成功，触发“单边账”
            } else {
                // 正常订单，微信和平台金额完全一致
                wechatBill.put(orderNo, platformBill.get(orderNo));
            }
        }
        // 故意额外塞入 1 条微信独有的订单（模拟渠道单边账：用户付了钱，但平台没发货）
        wechatBill.put("WX_ONLY_999999", new BigDecimal("99.00"));
        XxlJobHelper.log("微信账单解析完毕，总数: " + wechatBill.size());

        // 3. 【核心对账逻辑：双向比对】
        XxlJobHelper.log("3. 开启高并发内存双向比对...");
        int diffCount = 0;

        // 3.1 遍历平台账单，对比微信账单
        for (Map.Entry<String, BigDecimal> platformEntry : platformBill.entrySet()) {
            String orderNo = platformEntry.getKey();
            BigDecimal platformAmount = platformEntry.getValue();
            BigDecimal wechatAmount = wechatBill.get(orderNo);

            if (wechatAmount == null) {
                saveDiff(orderNo, null, 1); // 1-平台单边账 (掉单)
                diffCount++;
            } else if (platformAmount.compareTo(wechatAmount) != 0) {
                saveDiff(orderNo, null, 3); // 3-金额不一致
                diffCount++;
            }
            wechatBill.remove(orderNo); // 对比过的移除，节约后续遍历时间
        }

        // 3.2 剩下的全是微信单边账
        for (Map.Entry<String, BigDecimal> wechatEntry : wechatBill.entrySet()) {
            saveDiff(wechatEntry.getKey(), null, 2); // 2-渠道单边账
            diffCount++;
        }

        long cost = System.currentTimeMillis() - startTime;
        XxlJobHelper.log("====== 对账结束！ ======");
        XxlJobHelper.log("⚡ 对账总耗时: " + cost + " ms (包含数据库IO与内存比对)");
        XxlJobHelper.log("🎯 共发现异常差异数据: " + diffCount + " 条，已入库预警！");
    }

    private void saveDiff(String orderNo, String tradeNo, int diffType) {
        String correctSql = "INSERT INTO t_reconciliation_diff (order_no, trade_no, diff_type, status) VALUES (?, ?, ?, 0)";
        jdbcTemplate.update(correctSql, orderNo, tradeNo, diffType);
    }
}
