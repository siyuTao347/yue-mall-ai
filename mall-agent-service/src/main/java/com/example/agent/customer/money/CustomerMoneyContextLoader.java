package com.example.agent.customer.money;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.example.agent.customer.CustomerJson.*;

/**
 * 资金调查上下文加载器
 * <p>
 * 在 LOAD_CONTEXT 阶段按鉴权用户加载最近的活动调查案件绑定（限制5条），
 * 注入至运行上下文的 entityRegistry.moneyCases 中，避免扫描其他用户案件。
 * </p>
 */
@Slf4j
@Component
public class CustomerMoneyContextLoader {

    private final AgentMoneyBindingMapper bindingMapper;

    public CustomerMoneyContextLoader(AgentMoneyBindingMapper bindingMapper) {
        this.bindingMapper = bindingMapper;
    }

    public void loadContext(Long userId, ObjectNode context) {
        if (userId == null || userId <= 0 || context == null) {
            return;
        }

        try {
            List<AgentMoneyBindingEntity> bindings = bindingMapper.selectList(new LambdaQueryWrapper<AgentMoneyBindingEntity>()
                    .eq(AgentMoneyBindingEntity::getUserId, userId)
                    .orderByDesc(AgentMoneyBindingEntity::getUpdatedAt)
                    .last("LIMIT 5"));

            if (bindings == null || bindings.isEmpty()) {
                return;
            }

            JsonNode entityRegistryNode = context.path("entityRegistry");
            ObjectNode entityRegistry;
            if (entityRegistryNode instanceof ObjectNode obj) {
                entityRegistry = obj;
            } else {
                entityRegistry = object();
                context.set("entityRegistry", entityRegistry);
            }

            ArrayNode moneyCasesArray = array();
            int currentTurn = context.path("turnNo").asInt(0);

            for (AgentMoneyBindingEntity b : bindings) {
                ObjectNode item = object();
                item.put("moneyCaseNo", b.getCaseNo());
                item.put("value", b.getCaseNo());
                item.put("orderNo", b.getOrderNo() != null ? b.getOrderNo() : "");
                item.put("caseState", b.getCaseState() != null ? b.getCaseState() : "OPEN");
                item.put("taskState", b.getTaskState() != null ? b.getTaskState() : "SUBMITTED");
                item.put("projectionRevision", b.getProjectionRevision() != null ? b.getProjectionRevision() : 1L);
                item.put("source", "VERIFIED_MONEY_BINDING");
                item.put("confirmed", true);
                item.put("lastMentionedTurn", currentTurn);
                moneyCasesArray.add(item);
            }

            entityRegistry.set("moneyCases", moneyCasesArray);
            log.debug("成功为用户 uid={} 加载 {} 条资金案件上下文", userId, bindings.size());
        } catch (Exception e) {
            log.warn("加载用户资金上下文异常, uid={}", userId, e);
        }
    }
}

