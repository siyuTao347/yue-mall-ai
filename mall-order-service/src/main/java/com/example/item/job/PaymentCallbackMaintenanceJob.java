package com.example.item.job;

import com.example.item.mapper.PaymentCallbackNonceMapper;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
public class PaymentCallbackMaintenanceJob {

    private static final long NONCE_RETENTION_DAYS = 1;

    private final PaymentCallbackNonceMapper nonceMapper;

    public PaymentCallbackMaintenanceJob(PaymentCallbackNonceMapper nonceMapper) {
        this.nonceMapper = nonceMapper;
    }

    @XxlJob("paymentCallbackNonceCleanupJob")
    public void cleanupExpiredNonces() {
        int deleted = nonceMapper.deleteExpiredBefore(LocalDateTime.now().minusDays(NONCE_RETENTION_DAYS));
        XxlJobHelper.log("支付回调 nonce 清理完成，删除数量: " + deleted);
    }
}
