package com.example.item.reconciliation.job;

import com.example.item.payment.application.PaymentConfirmationService;
import com.example.item.payment.channel.ChannelPaymentCommands.VerifiedChannelEvent;
import com.example.item.payment.domain.PaymentEventInbox;
import com.example.item.payment.domain.PaymentMethod;
import com.example.item.payment.mapper.PaymentEventInboxMapper;
import com.example.item.payment.mock.*;
import com.example.item.reconciliation.application.ReconciliationExecutionService;
import com.example.item.reconciliation.domain.ReconFile;
import com.example.item.reconciliation.mapper.ReconFileMapper;
import com.example.item.reconciliation.statement.ReconciliationStatementParser;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Component
@Slf4j
public class MultiChannelReconciliationJobHandler {

    private final MockNotificationOutboxMapper outboxMapper;
    private final PaymentEventInboxMapper inboxMapper;
    private final MockChannelEngine mockChannelEngine;
    private final PaymentConfirmationService confirmationService;
    private final MockDailyStatementService statementService;
    private final ReconciliationStatementParser statementParser;
    private final ReconciliationExecutionService executionService;
    private final ReconFileMapper fileMapper;

    public MultiChannelReconciliationJobHandler(
            MockNotificationOutboxMapper outboxMapper,
            PaymentEventInboxMapper inboxMapper,
            MockChannelEngine mockChannelEngine,
            PaymentConfirmationService confirmationService,
            MockDailyStatementService statementService,
            ReconciliationStatementParser statementParser,
            ReconciliationExecutionService executionService,
            ReconFileMapper fileMapper
    ) {
        this.outboxMapper = outboxMapper;
        this.inboxMapper = inboxMapper;
        this.mockChannelEngine = mockChannelEngine;
        this.confirmationService = confirmationService;
        this.statementService = statementService;
        this.statementParser = statementParser;
        this.executionService = executionService;
        this.fileMapper = fileMapper;
    }

    /**
     * 定时或 XXL-Job 派发模拟渠道 Outbox 通知
     */
    @Scheduled(fixedDelay = 3000)
    @XxlJob("mockNotificationOutboxDispatchJob")
    public void dispatchOutboxNotifications() {
        LocalDateTime now = LocalDateTime.now();
        List<MockNotificationOutbox> pending = outboxMapper.selectPendingOutbox(now, 50);
        if (pending.isEmpty()) {
            return;
        }

        for (MockNotificationOutbox outbox : pending) {
            try {
                // 验证签名
                if (!mockChannelEngine.verifyNotificationSignature(
                        outbox.getProfileId(), outbox.getPayloadJson(), outbox.getSignature())) {
                    log.error("Outbox signature mismatch for eventId: {}", outbox.getEventId());
                    outbox.setStatus("FAILED");
                    outbox.setRetryCount(outbox.getRetryCount() + 1);
                    outbox.setNextSendAt(now.plusMinutes(5));
                    outbox.setUpdatedTime(now);
                    outboxMapper.updateById(outbox);
                    continue;
                }

                // 入库 Inbox
                PaymentEventInbox existingInbox = inboxMapper.selectByEventId(outbox.getEventId());
                if (existingInbox == null) {
                    existingInbox = PaymentEventInbox.builder()
                            .eventId(outbox.getEventId())
                            .profileId(outbox.getProfileId())
                            .channelMerchantNo(outbox.getChannelMerchantNo())
                            .eventType(outbox.getEventType())
                            .verifiedPayload(outbox.getPayloadJson())
                            .source("MOCK_OUTBOX")
                            .processingStatus("RECEIVED")
                            .nextExecuteAt(now)
                            .version(0L)
                            .createdTime(now)
                            .updatedTime(now)
                            .build();
                    inboxMapper.insert(existingInbox);
                }

                // 立即触发确认编排
                VerifiedChannelEvent event = mockChannelEngine.parseVerifiedEvent(
                        outbox.getPayloadJson(), outbox.getSignature());
                confirmationService.acceptVerifiedFact(event);

                // 更新 Inbox 与 Outbox
                existingInbox.setProcessingStatus("PROCESSED");
                existingInbox.setUpdatedTime(now);
                inboxMapper.updateById(existingInbox);

                outbox.setStatus("SENT");
                outbox.setUpdatedTime(now);
                outboxMapper.updateById(outbox);

            } catch (Exception e) {
                log.warn("Failed to dispatch outbox notification for {}: {}", outbox.getEventId(), e.getMessage());
                outbox.setRetryCount(outbox.getRetryCount() + 1);
                outbox.setStatus(outbox.getRetryCount() >= 5 ? "ABANDONED" : "FAILED");
                outbox.setNextSendAt(now.plusSeconds((long) Math.pow(2, outbox.getRetryCount()) * 5));
                outbox.setUpdatedTime(now);
                outboxMapper.updateById(outbox);
            }
        }
    }

    /**
     * 每日外部渠道自动对账 Job
     */
    @XxlJob("channelDailyReconciliationJob")
    public void executeDailyReconciliation() {
        String param = XxlJobHelper.getJobParam();
        LocalDate targetDate = (param != null && !param.isBlank())
                ? LocalDate.parse(param.trim())
                : LocalDate.now().minusDays(1);

        XxlJobHelper.log("开始执行全渠道日切对账，业务日: " + targetDate);

        for (PaymentMethod method : PaymentMethod.values()) {
            try {
                var run = executionService.executeReconciliation(method, "TRADE", targetDate, "XXL_JOB_DAILY");
                XxlJobHelper.log("渠道 [" + method.name() + "] 对账执行完成，批次号: " + run.getRunNo()
                        + ", 差异数: " + (run.getDiffCount() != null ? run.getDiffCount() : 0));
            } catch (Exception e) {
                log.error("Channel daily reconciliation error for {}", method, e);
                XxlJobHelper.log("渠道 [" + method.name() + "] 对账异常: " + e.getMessage());
            }
        }

        XxlJobHelper.log("全渠道日切对账执行完毕");
    }
}
