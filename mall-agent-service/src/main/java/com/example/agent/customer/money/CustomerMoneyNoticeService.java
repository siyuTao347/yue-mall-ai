package com.example.agent.customer.money;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;

import static com.example.agent.customer.CustomerJson.error;

/**
 * 资金案件通知处理服务
 * 接收来自 Money 微服务的变更通知，落库 Inbox 并唤醒状态同步
 */
@Slf4j
@Service
public class CustomerMoneyNoticeService {

    private final AgentMoneyNoticeInboxMapper inboxMapper;
    private final AgentMoneyBindingMapper bindingMapper;
    private final CustomerMoneyTaskSyncWorker syncWorker;
    private final ObjectMapper objectMapper;

    public CustomerMoneyNoticeService(AgentMoneyNoticeInboxMapper inboxMapper,
                                      AgentMoneyBindingMapper bindingMapper,
                                      CustomerMoneyTaskSyncWorker syncWorker,
                                      ObjectMapper objectMapper) {
        this.inboxMapper = inboxMapper;
        this.bindingMapper = bindingMapper;
        this.syncWorker = syncWorker;
        this.objectMapper = objectMapper;
    }

    public CustomerMoneyNoticeService(AgentMoneyNoticeInboxMapper inboxMapper,
                                      AgentMoneyBindingMapper bindingMapper,
                                      CustomerMoneyTaskSyncWorker syncWorker) {
        this(inboxMapper, bindingMapper, syncWorker, new ObjectMapper());
    }

    public boolean recordAndProcessNotice(String source, String eventId, String payloadJson) {
        if (source == null || eventId == null || payloadJson == null) {
            throw error(400, "INVALID_ARGUMENT", "通知参数不能为空");
        }

        String payloadHash = sha256(payloadJson);
        LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC);

        // 1. Inbox 持久化去重
        AgentMoneyNoticeInboxEntity existing = inboxMapper.selectOne(new LambdaQueryWrapper<AgentMoneyNoticeInboxEntity>()
                .eq(AgentMoneyNoticeInboxEntity::getSource, source)
                .eq(AgentMoneyNoticeInboxEntity::getEventId, eventId));

        if (existing != null) {
            log.info("忽略重复通知, source={}, eventId={}", source, eventId);
            return true;
        }

        AgentMoneyNoticeInboxEntity inbox = AgentMoneyNoticeInboxEntity.builder()
                .source(source)
                .eventId(eventId)
                .payloadHash(payloadHash)
                .payloadJson(payloadJson)
                .state("PENDING")
                .nextAttemptAt(nowUtc)
                .attempts(1)
                .version(1L)
                .createdAt(nowUtc)
                .updatedAt(nowUtc)
                .build();
        inboxMapper.insert(inbox);

        // 2. 解析通知负载并唤醒同步
        try {
            JsonNode root = objectMapper.readTree(payloadJson);
            String caseNo = root.path("caseNo").asText(null);
            String orderNo = root.path("orderNo").asText(null);

            if (caseNo != null && !caseNo.isBlank()) {
                List<AgentMoneyBindingEntity> bindings = bindingMapper.selectList(new LambdaQueryWrapper<AgentMoneyBindingEntity>()
                        .eq(AgentMoneyBindingEntity::getCaseNo, caseNo));
                if (bindings != null && !bindings.isEmpty()) {
                    for (AgentMoneyBindingEntity b : bindings) {
                        b.setNextPollAt(nowUtc);
                        bindingMapper.updateById(b);
                        if (syncWorker != null) {
                            syncWorker.syncOneBinding(b);
                        }
                    }
                }
            }

            inbox.setState("PROCESSED");
            inbox.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
            inboxMapper.updateById(inbox);
            return true;
        } catch (Exception e) {
            log.warn("处理 Notice 异常, eventId={}", eventId, e);
            inbox.setState("FAILED");
            inbox.setLastError(e.getMessage());
            inbox.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
            inboxMapper.updateById(inbox);
            return false;
        }
    }

    private static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            return "hash_" + text.hashCode();
        }
    }
}

