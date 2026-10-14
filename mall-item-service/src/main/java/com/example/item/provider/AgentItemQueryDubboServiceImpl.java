package com.example.item.provider;

import api.agent.AgentItemQueryDubboService;
import com.example.item.entity.Item;
import com.example.item.mapper.ItemMapper;
import org.apache.dubbo.config.annotation.DubboService;
import org.springframework.stereotype.Service;

/** Public, read-only item facts for CustomerAgent. Sensitive seller and detail fields are omitted. */
@Service
@DubboService
public class AgentItemQueryDubboServiceImpl implements AgentItemQueryDubboService {
    private final ItemMapper items;

    public AgentItemQueryDubboServiceImpl(ItemMapper items) {
        this.items = items;
    }

    @Override
    public PublicItemFact queryPublicItem(long itemId) {
        if (itemId <= 0) throw new IllegalArgumentException("Invalid item id");
        Item item = items.selectById(itemId);
        if (item == null || !Integer.valueOf(1).equals(item.getStatus())
            || !"APPROVED".equalsIgnoreCase(item.getAuditStatus())
            || isBlocked(item.getRiskStatus())) {
            throw new IllegalArgumentException("商品不存在或未公开销售");
        }
        int stock = Math.max(0, (item.getStock() == null ? 0 : item.getStock())
            - (item.getFrozenStock() == null ? 0 : item.getFrozenStock()));
        return new PublicItemFact(item.getId(), item.getItemName(), item.getPrice(), stock,
            item.getDeliveryMode(), item.getSubTitle());
    }

    private static boolean isBlocked(String status) {
        return "FROZEN".equalsIgnoreCase(status) || "REJECTED".equalsIgnoreCase(status)
            || "BLOCKED".equalsIgnoreCase(status);
    }
}
