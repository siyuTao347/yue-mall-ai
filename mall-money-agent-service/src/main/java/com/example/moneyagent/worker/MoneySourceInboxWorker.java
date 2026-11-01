package com.example.moneyagent.worker;

import api.agent.money.MoneyProblemFamily;
import api.agent.money.contract.MoneyInvestigationRequest;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.moneyagent.application.MoneyCaseService;
import com.example.moneyagent.persistence.entity.MoneySourceInboxEntity;
import com.example.moneyagent.persistence.mapper.MoneySourceInboxMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 资金领域事件接收与自主调查触发 Worker (MoneySourceInboxWorker)
 * 消费来自 Order/User 等领域事件 (t_money_source_inbox)，按最新业务版本触发自主调查建案
 */
@Slf4j
@Component
public class MoneySourceInboxWorker {

    private final MoneySourceInboxMapper inboxMapper;
    private final MoneyCaseService caseService;
    private final ObjectMapper objectMapper;

    public MoneySourceInboxWorker(MoneySourceInboxMapper inboxMapper,
                                  MoneyCaseService caseService,
                                  ObjectMapper objectMapper) {
        this.inboxMapper = inboxMapper;
        this.caseService = caseService;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelay = 5000)
    public void processPendingInboxEvents() {
        LocalDateTime now = LocalDateTime.now();
        List<MoneySourceInboxEntity> pendingList = inboxMapper.selectList(
                new LambdaQueryWrapper<MoneySourceInboxEntity>()
                        .in(MoneySourceInboxEntity::getState, List.of("PENDING", "RETRY_WAIT"))
                        .le(MoneySourceInboxEntity::getNextAttemptAt, now)
                        .orderByAsc(MoneySourceInboxEntity::getId)
                        .last("LIMIT 20")
        );

        if (pendingList == null || pendingList.isEmpty()) {
            return;
        }

        for (MoneySourceInboxEntity entity : pendingList) {
            processOneEvent(entity, now);
        }
    }

    public boolean processOneEvent(MoneySourceInboxEntity entity, LocalDateTime now) {
        try {
            entity.setAttemptCount(entity.getAttemptCount() + 1);

            String aggType = entity.getAggregateType();
            String aggRef = entity.getAggregateRef();
            MoneyProblemFamily problemFamily = resolveProblemFamily(aggType);

            JsonNode payload = null;
            if (entity.getPayloadJson() != null && !entity.getPayloadJson().isBlank()) {
                payload = objectMapper.readTree(entity.getPayloadJson());
            }

            String orderNo = payload != null && payload.has("orderNo") ? payload.get("orderNo").asText() : null;
            if (orderNo == null && aggRef != null && aggRef.startsWith("T")) {
                orderNo = aggRef;
            }

            MoneyInvestigationRequest.Subject subject = MoneyInvestigationRequest.Subject.builder()
                    .scopeKey(orderNo != null ? "ORDER:" + orderNo : "RECON:" + aggRef)
                    .orderNo(orderNo)
                    .diffNo("RECON_DIFF".equalsIgnoreCase(aggType) ? aggRef : null)
                    .attemptNo("PAYMENT_ATTEMPT".equalsIgnoreCase(aggType) ? aggRef : null)
                    .refundNo("REFUND_ORDER".equalsIgnoreCase(aggType) ? aggRef : null)
                    .build();

            MoneyInvestigationRequest request = MoneyInvestigationRequest.builder()
                    .schema(MoneyInvestigationRequest.SCHEMA_V1)
                    .requestId("INBOX_" + entity.getSource() + "_" + entity.getEventId())
                    .origin("DOMAIN_EVENT")
                    .subject(subject)
                    .problemFamily(problemFamily)
                    .claim(MoneyInvestigationRequest.Claim.builder()
                            .text("系统自主领域事件触发调查: " + aggType + " / " + aggRef)
                            .sourceMessageRef("EVENT:" + entity.getEventId())
                            .reportedAt(now.toString())
                            .build())
                    .callerContext(MoneyInvestigationRequest.CallerContext.builder()
                            .dispatchNo("INBOX_" + entity.getId())
                            .intentInstanceId("AUTONOMOUS_" + entity.getEventId())
                            .build())
                    .build();

            // 自主调查触发建案
            caseService.startInvestigation(request);

            entity.setState("PROCESSED");
            entity.setUpdatedAt(now);
            entity.setLastError(null);
            inboxMapper.updateById(entity);
            log.info("自主调查事件处理成功: id={}, eventId={}, aggType={}, aggRef={}",
                    entity.getId(), entity.getEventId(), aggType, aggRef);
            return true;
        } catch (Exception e) {
            log.warn("自主调查事件处理异常: id={}, eventId={}, err={}", entity.getId(), entity.getEventId(), e.getMessage());
            entity.setLastError(e.getMessage() != null && e.getMessage().length() > 60 ? e.getMessage().substring(0, 60) : e.getMessage());
            if (entity.getAttemptCount() >= 5) {
                entity.setState("FAILED");
            } else {
                entity.setState("RETRY_WAIT");
                entity.setNextAttemptAt(now.plusSeconds(30L * entity.getAttemptCount()));
            }
            entity.setUpdatedAt(now);
            inboxMapper.updateById(entity);
            return false;
        }
    }

    private MoneyProblemFamily resolveProblemFamily(String aggregateType) {
        if (aggregateType == null) return MoneyProblemFamily.PAYMENT_CONFIRMATION;
        return switch (aggregateType.toUpperCase()) {
            case "RECON_DIFF" -> MoneyProblemFamily.RECON_MISMATCH;
            case "REFUND_ORDER" -> MoneyProblemFamily.REFUND_STATUS;
            case "TRADE_OPERATION" -> MoneyProblemFamily.EXCESS_PAYMENT;
            default -> MoneyProblemFamily.PAYMENT_CONFIRMATION;
        };
    }
}
