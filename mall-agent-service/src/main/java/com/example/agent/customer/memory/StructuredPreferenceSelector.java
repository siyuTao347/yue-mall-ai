package com.example.agent.customer.memory;

import com.example.agent.customer.memory.MemoryModels.MemoryRecord;
import com.example.agent.customer.memory.MemoryModels.MemoryType;
import com.example.agent.customer.memory.MemoryModels.Polarity;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class StructuredPreferenceSelector {

    public List<MemoryRecord> selectApplicableMemories(
        List<MemoryRecord> activeMemories,
        String question,
        JsonNode intentNode,
        JsonNode contextEntities,
        int injectLimit
    ) {
        if (activeMemories == null || activeMemories.isEmpty()) {
            return Collections.emptyList();
        }

        // Rule: If user states "帮朋友买", "送人", etc. (third-party purchase for this turn),
        // do not apply user's personal habitual preferences!
        if (question != null && question.matches("(?s).*(帮朋友|送朋友|送人|替别人|代买|借用).*")) {
            return Collections.emptyList();
        }

        List<MemoryRecord> selected = new ArrayList<>();
        String q = question != null ? question : "";

        for (MemoryRecord r : activeMemories) {
            // Check conflict with current explicit input
            if (isConflictingWithCurrentInput(r, q)) {
                continue;
            }

            // Exclusions always take priority
            if (r.polarity() == Polarity.EXCLUDE || r.memoryType() == MemoryType.EXCLUSION) {
                selected.add(r);
                continue;
            }

            // Category match or delivery mode relevance
            if (r.memoryType() == MemoryType.DELIVERY_PREFERENCE) {
                // Relevant for general purchasing or when asking about delivery mode
                selected.add(r);
            } else if (r.memoryType() == MemoryType.CATEGORY_PREFERENCE || r.memoryType() == MemoryType.PLATFORM_PREFERENCE) {
                selected.add(r);
            } else if (r.memoryType() == MemoryType.PRICE_RANGE || r.memoryType() == MemoryType.SPEC_PREFERENCE) {
                // If question explicitly specifies a one-time budget or spec, don't inject conflicting history
                if (!q.matches("(?s).*(这次|今天|最多|预算|价格).*")) {
                    selected.add(r);
                }
            } else {
                selected.add(r);
            }

            if (selected.size() >= injectLimit) {
                break;
            }
        }

        return selected;
    }

    private boolean isConflictingWithCurrentInput(MemoryRecord r, String q) {
        if (r.memoryType() == MemoryType.DELIVERY_PREFERENCE) {
            String mode = r.valueJson().path("deliveryMode").asText("");
            if ("AUTO".equalsIgnoreCase(mode) && q.matches("(?s).*(这次|只考虑|改成|换成)?.*人工发货.*")) {
                return true;
            }
            if ("MANUAL".equalsIgnoreCase(mode) && q.matches("(?s).*(这次|只考虑|改成|换成)?.*自动发货.*")) {
                return true;
            }
        }
        if (r.memoryType() == MemoryType.PRICE_RANGE) {
            if (q.matches("(?s).*(这次|今天)?.*预算\\s*\\d+.*")) {
                return true;
            }
        }
        return false;
    }
}
