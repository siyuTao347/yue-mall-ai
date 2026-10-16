package com.example.agent.customer.memory;

import com.example.agent.customer.memory.MemoryModels.MemoryRecord;
import com.example.agent.customer.memory.MemoryModels.MemoryType;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class MemoryQueryEnricher {

    public String enrichKnowledgeQuery(String baseQuery, List<MemoryRecord> memories) {
        if (memories == null || memories.isEmpty() || baseQuery == null) {
            return baseQuery;
        }

        StringBuilder enrich = new StringBuilder();
        for (MemoryRecord r : memories) {
            if (enrich.length() >= 120) break;

            if (r.memoryType() == MemoryType.DELIVERY_PREFERENCE) {
                String mode = r.valueJson().path("deliveryMode").asText("");
                String term = "AUTO".equalsIgnoreCase(mode) ? "自动发货" : "人工发货";
                if (!baseQuery.contains(term) && !enrich.toString().contains(term)) {
                    enrich.append(" ").append(term);
                }
            } else if (r.memoryType() == MemoryType.CATEGORY_PREFERENCE) {
                String cat = r.scopeJson().path("categoryCode").asText("");
                if (!cat.isBlank() && !baseQuery.contains(cat) && !enrich.toString().contains(cat)) {
                    enrich.append(" ").append(cat);
                }
            }
        }

        if (enrich.isEmpty()) {
            return baseQuery;
        }

        String combined = baseQuery + enrich;
        return combined.length() <= 512 ? combined : combined.substring(0, 512);
    }
}
