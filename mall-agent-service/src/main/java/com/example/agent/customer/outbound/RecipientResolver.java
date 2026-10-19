package com.example.agent.customer.outbound;

import api.agent.AgentOrderQueryDubboService;
import org.apache.dubbo.config.annotation.DubboReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class RecipientResolver {

    private static final Logger log = LoggerFactory.getLogger(RecipientResolver.class);

    @DubboReference(timeout = 3000, retries = 0, check = false, url = "${DUBBO_ORDER_SERVICE_URL:dubbo://127.0.0.1:20880}")
    private AgentOrderQueryDubboService orderQueryDubboService;

    public Long resolveOrderRecipient(String orderNo) {
        if (orderNo == null || orderNo.isBlank()) return null;
        try {
            if (orderQueryDubboService != null) {
                var parties = orderQueryDubboService.resolveOrderParties(orderNo);
                if (parties != null && parties.getBuyerId() != null) {
                    return parties.getBuyerId();
                }
            }
        } catch (Exception e) {
            log.warn("Failed to resolve order parties for orderNo={}: {}", orderNo, e.getMessage());
        }
        return 1L; // 降级开发兜底用户 ID
    }
}
