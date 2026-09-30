package com.example.item.service;

import api.risk.RiskSupport;
import com.example.item.config.TradeOrchestrationProperties;
import com.example.item.dto.TradeOrchestrationContext;
import com.example.item.entity.TradeOrder;
import com.example.item.entity.TradeOrchestrationStep;
import com.example.item.entity.TradeOrchestrationTask;
import com.example.item.mapper.TradeOrchestrationStepMapper;
import com.example.item.mapper.TradeOrchestrationTaskMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.InetAddress;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Slf4j
public class TradeOrchestrationService {
    private static final String INIT = "INIT";
    private static final String RUNNING = "RUNNING";
    private static final String SUCCESS = "SUCCESS";
    private static final String FAILED = "FAILED";
    private static final String MANUAL_PENDING = "MANUAL_PENDING";
    private static final String COMPENSATED = "COMPENSATED";

    private final TradeOrchestrationTaskMapper taskMapper;
    private final TradeOrchestrationStepMapper stepMapper;
    private final TradeOrchestrationCommandService commandService;
    private final TradeOrchestrationPublisher publisher;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;
    private final TradeOrchestrationProperties properties;
    private final String workerId;

    public TradeOrchestrationService(
            TradeOrchestrationTaskMapper taskMapper,
            TradeOrchestrationStepMapper stepMapper,
            TradeOrchestrationCommandService commandService,
            TradeOrchestrationPublisher publisher,
            TransactionTemplate transactionTemplate,
            ObjectMapper objectMapper,
            TradeOrchestrationProperties properties
    ) {
        this.taskMapper = taskMapper;
        this.stepMapper = stepMapper;
        this.commandService = commandService;
        this.publisher = publisher;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.workerId = resolveWorkerId();
    }

    public TradeOrchestrationTask createPaymentConfirmTask(TradeOrder order, String paymentNo) {
        TradeOrchestrationContext context = new TradeOrchestrationContext(
                order.getOrderNo(), paymentNo, null, null, null, null, order.getBuyerId(),
                order.getSellerId(), order.getMerchantId(), order.getOrderAmount(), null, null, null);
        return createTask(
                "PAY-" + order.getOrderNo(),
                "PAY_CONFIRM",
                "ORDER",
                order.getOrderNo(),
                "PAY_CONFIRM:" + order.getOrderNo(),
                context,
                List.of(
                        step(1, "FUND_FREEZE", "REMOTE", "FUND_FREEZE:" + order.getOrderNo()),
                        step(2, "ASSET_CONFIRM", "REMOTE", "ASSET_CONFIRM:" + order.getOrderNo()),
                        step(3, "LOCAL_PAYMENT_CONFIRM", "LOCAL", "PAY_CONFIRM_LOCAL:" + order.getOrderNo()),
                        step(4, "ESCROW_CONFIRM", "LOCAL", "ESCROW_CONFIRM:" + order.getOrderNo())
                )
        );
    }

    public TradeOrchestrationTask createOrderCreateTask(
            TradeOrder order,
            String eventNo,
            String ipHash,
            String deviceHash,
            java.util.Map<String, Object> payload
    ) {
        TradeOrchestrationContext context = new TradeOrchestrationContext(
                order.getOrderNo(), null, null, null, null, null, order.getBuyerId(),
                null, null, null, null, null, null,
                order.getItemId(), order.getQuantity(), eventNo, "ORDER", "CREATE",
                ipHash, deviceHash, payload);
        return createTask(
                "ORDER-" + order.getOrderNo(),
                "ORDER_CREATE",
                "ORDER",
                order.getOrderNo(),
                "ORDER_CREATE:" + order.getOrderNo(),
                context,
                List.of(
                        step(1, "RISK_PRECHECK", "REMOTE", "RISK_PRECHECK:" + eventNo),
                        step(2, "ASSET_RESERVE", "REMOTE", "ASSET_RESERVE:" + order.getOrderNo()),
                        step(3, "ORDER_READY", "LOCAL", "ORDER_READY:" + order.getOrderNo()),
                        step(4, "RISK_CONFIRM", "REMOTE", "RISK_CONFIRM:" + eventNo)
                )
        );
    }

    public TradeOrchestrationTask createRiskEventTask(
            TradeOrder order,
            String scene,
            String eventType,
            String ipHash,
            String deviceHash,
            java.util.Map<String, Object> payload
    ) {
        String eventNo = RiskSupport.nextEventNo(scene);
        TradeOrchestrationContext context = new TradeOrchestrationContext(
                order.getOrderNo(), null, null, null, null, null, order.getBuyerId(),
                order.getSellerId(), order.getMerchantId(), order.getOrderAmount(), null, null, null,
                order.getItemId(), order.getQuantity(), eventNo, scene, eventType,
                ipHash, deviceHash, payload);
        return createTask(
                eventNo,
                "RISK_EVENT_CONFIRM",
                "ORDER",
                order.getOrderNo(),
                "RISK_EVENT:" + eventNo,
                context,
                List.of(
                        step(1, "RISK_RECORD_EVENT", "REMOTE", "RISK_RECORD:" + eventNo),
                        step(2, "RISK_CONFIRM_EVENT", "REMOTE", "RISK_CONFIRM:" + eventNo)
                )
        );
    }

    public TradeOrchestrationTask createRiskEventConfirmTask(TradeOrder order, String eventNo) {
        TradeOrchestrationContext context = new TradeOrchestrationContext(
                order.getOrderNo(), null, null, null, null, null, order.getBuyerId(),
                order.getSellerId(), order.getMerchantId(), order.getOrderAmount(), null, null, null,
                order.getItemId(), order.getQuantity(), eventNo, null, null, null, null, null);
        return createTask(
                eventNo,
                "RISK_EVENT_CONFIRM",
                "ORDER",
                order.getOrderNo(),
                "RISK_CONFIRM:" + eventNo,
                context,
                List.of(step(1, "RISK_CONFIRM_EVENT", "REMOTE", "RISK_CONFIRM:" + eventNo))
        );
    }

    public TradeOrchestrationTask createMerchantDisputeTask(TradeOrder order, String disputeNo) {
        TradeOrchestrationContext context = new TradeOrchestrationContext(
                order.getOrderNo(), null, disputeNo, null, null, null, order.getBuyerId(),
                order.getSellerId(), order.getMerchantId(), order.getOrderAmount(), null, null, null);
        return createTask(
                "MERCHANT-DISPUTE-" + order.getOrderNo(),
                "MERCHANT_DISPUTE",
                "ORDER",
                order.getOrderNo(),
                "MERCHANT_DISPUTE:" + order.getOrderNo(),
                context,
                List.of(step(1, "MERCHANT_DISPUTE", "REMOTE",
                        "MERCHANT_DISPUTE:" + order.getOrderNo()))
        );
    }

    public TradeOrchestrationTask createMerchantCompleteTask(TradeOrder order, BigDecimal score) {
        TradeOrchestrationContext context = new TradeOrchestrationContext(
                order.getOrderNo(), null, null, null, null, null, order.getBuyerId(),
                order.getSellerId(), order.getMerchantId(), order.getOrderAmount(), null, null, null,
                null, null, null, null, null, null, null, Map.of("score", score));
        return createTask(
                "MERCHANT-COMPLETE-" + order.getOrderNo(),
                "MERCHANT_COMPLETE",
                "ORDER",
                order.getOrderNo(),
                "MERCHANT_COMPLETE:" + order.getOrderNo(),
                context,
                List.of(step(1, "MERCHANT_COMPLETE", "REMOTE",
                        "MERCHANT_COMPLETE:" + order.getOrderNo()))
        );
    }

    public TradeOrchestrationTask createSettlementTask(TradeOrder order,
                                                       BigDecimal feeAmount,
                                                       BigDecimal sellerIncome) {
        TradeOrchestrationContext context = new TradeOrchestrationContext(
                order.getOrderNo(), null, null, null, null, null, order.getBuyerId(), order.getSellerId(),
                order.getMerchantId(), order.getOrderAmount(), feeAmount, sellerIncome, null);
        return createTask(
                "SETTLE-" + order.getOrderNo(),
                "SETTLE",
                "ORDER",
                order.getOrderNo(),
                "SETTLE:" + order.getOrderNo(),
                context,
                List.of(
                        step(1, "FUND_SETTLE", "REMOTE", "FUND_SETTLE:" + order.getOrderNo()),
                        step(2, "SAVE_SETTLEMENT", "LOCAL", "SETTLEMENT_SAVE:" + order.getOrderNo()),
                        step(3, "SETTLE_AVAILABLE", "REMOTE", "FUND_SETTLE_AVAILABLE:" + order.getOrderNo()),
                        step(4, "MARK_SETTLED", "LOCAL", "SETTLE_LOCAL:" + order.getOrderNo())
                )
        );
    }

    public TradeOrchestrationTask createArbitrationRefundTask(TradeOrder order,
                                                              String disputeNo,
                                                              Long adminId,
                                                              String reason) {
        TradeOrchestrationContext context = new TradeOrchestrationContext(
                order.getOrderNo(), null, disputeNo, "REFUND_ALL", reason, adminId, order.getBuyerId(),
                order.getSellerId(), order.getMerchantId(), order.getOrderAmount(), null, null, null);
        return createTask(
                "ARB-REFUND-" + disputeNo,
                "ARBITRATION_REFUND",
                "DISPUTE",
                disputeNo,
                "ARBITRATION_REFUND:" + disputeNo,
                context,
                List.of(
                        step(1, "FUND_REFUND", "REMOTE", "FUND_REFUND:" + order.getOrderNo()),
                        step(2, "ASSET_INVALIDATE", "REMOTE", "ASSET_INVALIDATE:" + order.getOrderNo()),
                        step(3, "MERCHANT_REFUND", "REMOTE", "MERCHANT_REFUND:" + order.getOrderNo()),
                        step(4, "LOCAL_ARBITRATION_REFUND", "LOCAL", "ARBITRATION_REFUND_LOCAL:" + disputeNo),
                        step(5, "LOCAL_ARBITRATION_RESOLVE", "LOCAL", "ARBITRATION_RESOLVE:" + disputeNo)
                )
        );
    }

    public TradeOrchestrationTask createArbitrationReleaseTask(TradeOrder order,
                                                               String disputeNo,
                                                               Long adminId,
                                                               String reason,
                                                               BigDecimal feeAmount,
                                                               BigDecimal sellerIncome) {
        TradeOrchestrationContext context = new TradeOrchestrationContext(
                order.getOrderNo(), null, disputeNo, "RELEASE_ALL", reason, adminId, order.getBuyerId(),
                order.getSellerId(), order.getMerchantId(), order.getOrderAmount(), feeAmount,
                sellerIncome, null);
        return createTask(
                "ARB-RELEASE-" + disputeNo,
                "ARBITRATION_RELEASE",
                "DISPUTE",
                disputeNo,
                "ARBITRATION_RELEASE:" + disputeNo,
                context,
                List.of(
                        step(1, "FUND_SETTLE", "REMOTE", "FUND_SETTLE:" + order.getOrderNo()),
                        step(2, "SAVE_SETTLEMENT", "LOCAL", "SETTLEMENT_SAVE:" + order.getOrderNo()),
                        step(3, "SETTLE_AVAILABLE", "REMOTE", "FUND_SETTLE_AVAILABLE:" + order.getOrderNo()),
                        step(4, "MARK_SETTLED", "LOCAL", "SETTLE_LOCAL:" + order.getOrderNo()),
                        step(5, "LOCAL_ARBITRATION_RESOLVE", "LOCAL", "ARBITRATION_RESOLVE:" + disputeNo)
                )
        );
    }

    public TradeOrchestrationTask createAssetReleaseTask(TradeOrder order, String reason) {
        TradeOrchestrationContext context = new TradeOrchestrationContext(
                order.getOrderNo(), null, null, null, reason, null, order.getBuyerId(), order.getSellerId(),
                order.getMerchantId(), order.getOrderAmount(), null, null, null);
        return createTask(
                "ASSET-RELEASE-" + order.getOrderNo(),
                "ASSET_RELEASE",
                "ORDER",
                order.getOrderNo(),
                "ASSET_RELEASE:" + order.getOrderNo(),
                context,
                List.of(
                        step(1, "ASSET_RELEASE", "REMOTE", "ASSET_RELEASE:" + order.getOrderNo()),
                        step(2, "LOCAL_CANCELLED", "LOCAL", "ORDER_CANCEL_LOCAL:" + order.getOrderNo())
                )
        );
    }

    public TradeOrchestrationTask createDeliveryTimeoutRefundTask(TradeOrder order) {
        TradeOrchestrationContext context = new TradeOrchestrationContext(
                order.getOrderNo(), null, null, "REFUND_ALL", "卖家超过交付截止时间，系统自动全额退款",
                null, order.getBuyerId(), order.getSellerId(), order.getMerchantId(),
                order.getOrderAmount(), null, null, null);
        return createTask(
                "DELIVERY-REFUND-" + order.getOrderNo(),
                "DELIVERY_TIMEOUT_REFUND",
                "ORDER",
                order.getOrderNo(),
                "DELIVERY_TIMEOUT_REFUND:" + order.getOrderNo(),
                context,
                List.of(
                        step(1, "FUND_REFUND", "REMOTE", "FUND_REFUND:" + order.getOrderNo()),
                        step(2, "ASSET_INVALIDATE", "REMOTE", "ASSET_INVALIDATE:" + order.getOrderNo()),
                        step(3, "MERCHANT_REFUND", "REMOTE", "MERCHANT_REFUND:" + order.getOrderNo()),
                        step(4, "LOCAL_ARBITRATION_REFUND", "LOCAL", "DELIVERY_REFUND_LOCAL:" + order.getOrderNo())
                )
        );
    }

    public int recover() {
        if (!properties.enabled()) {
            return 0;
        }
        List<TradeOrchestrationTask> tasks = taskMapper.selectRecoverable(
                LocalDateTime.now(), properties.recoverBatchSize());
        int processed = 0;
        for (TradeOrchestrationTask task : tasks) {
            if (execute(task.getTaskNo())) {
                processed++;
            }
        }
        return processed;
    }

    public boolean execute(String taskNo) {
        if (!properties.enabled()) {
            return false;
        }
        TradeOrchestrationTask task = taskMapper.selectByTaskNo(taskNo);
        if (task == null) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        if (taskMapper.tryClaim(task.getId(), workerId, now.plusSeconds(properties.lockSeconds()), now) <= 0) {
            return false;
        }
        task = taskMapper.selectByTaskNo(taskNo);
        return executeClaimed(task);
    }

    private boolean executeClaimed(TradeOrchestrationTask task) {
        List<TradeOrchestrationStep> steps = stepMapper.selectByTaskNo(task.getTaskNo());
        for (TradeOrchestrationStep current : steps) {
            if (SUCCESS.equals(current.getStatus())) {
                continue;
            }
            LocalDateTime now = LocalDateTime.now();
            if (stepMapper.markRunning(current.getId(), now) <= 0) {
                taskMapper.releaseWaiting(
                        task.getTaskNo(),
                        waitingTaskStatus(current.getStatus()),
                        current.getNextExecuteTime(),
                        now
                );
                return false;
            }
            try {
                String response = commandService.execute(task, current);
                stepMapper.markSuccess(current.getId(), writeJson(response), LocalDateTime.now());
                task = captureFundTransaction(task, current, response);
            } catch (RuntimeException exception) {
                if (exception instanceof TradeOrchestrationTerminalException) {
                    LocalDateTime terminalTime = LocalDateTime.now();
                    stepMapper.markFailed(current.getId(), COMPENSATED,
                            truncate(exception.getMessage()), null, terminalTime);
                    taskMapper.finish(task.getTaskNo(), COMPENSATED, terminalTime);
                    log.warn("trade orchestration step reached business terminal state, taskNo={}, step={}, reason={}",
                            task.getTaskNo(), current.getStepName(), exception.getMessage());
                    return true;
                }
                handleStepFailure(task, current, exception);
                return false;
            }
        }
        taskMapper.finish(task.getTaskNo(), SUCCESS, LocalDateTime.now());
        return true;
    }

    private String waitingTaskStatus(String stepStatus) {
        return MANUAL_PENDING.equals(stepStatus) ? MANUAL_PENDING : FAILED;
    }

    private TradeOrchestrationTask captureFundTransaction(
            TradeOrchestrationTask task,
            TradeOrchestrationStep step,
            String response
    ) {
        if (!"FUND_SETTLE".equals(step.getStepName()) || response == null || response.isBlank()) {
            return task;
        }
        try {
            TradeOrchestrationContext context = objectMapper.readValue(
                    task.getContextJson(), TradeOrchestrationContext.class);
            context = context.withFundTransactionNo(response);
            task.setContextJson(writeJson(context));
            taskMapper.updateById(task);
        } catch (Exception exception) {
            throw new IllegalStateException("资金流水号写回编排上下文失败", exception);
        }
        return task;
    }

    private void handleStepFailure(
            TradeOrchestrationTask task,
            TradeOrchestrationStep step,
            RuntimeException exception
    ) {
        int attemptCount = (step.getAttemptCount() == null ? 0 : step.getAttemptCount()) + 1;
        int maxAttemptCount = step.getMaxAttemptCount() == null
                ? properties.maxRetryCount() : step.getMaxAttemptCount();
        int taskRetryCount = task.getRetryCount() == null ? 0 : task.getRetryCount();
        boolean manual = attemptCount >= maxAttemptCount || taskRetryCount >= task.getMaxRetryCount();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime nextTime = manual ? null : now.plusSeconds(properties.retryDelaySeconds());
        stepMapper.markFailed(step.getId(), manual ? MANUAL_PENDING : FAILED,
                truncate(exception.getMessage()), nextTime, now);
        taskMapper.markFailed(task.getTaskNo(), manual ? MANUAL_PENDING : FAILED,
                nextTime, now);
        log.warn("trade orchestration step failed, taskNo={}, step={}, manual={}",
                task.getTaskNo(), step.getStepName(), manual, exception);
    }

    private TradeOrchestrationTask createTask(
            String taskNo,
            String taskType,
            String bizType,
            String bizNo,
            String idempotencyKey,
            TradeOrchestrationContext context,
            List<StepDefinition> steps
    ) {
        TradeOrchestrationTask created = transactionTemplate.execute(status -> {
            LocalDateTime now = LocalDateTime.now();
            TradeOrchestrationTask task = new TradeOrchestrationTask();
            task.setTaskNo(taskNo);
            task.setTaskType(taskType);
            task.setBizType(bizType);
            task.setBizNo(bizNo);
            task.setStatus(INIT);
            task.setContextJson(writeJson(context));
            task.setIdempotencyKey(idempotencyKey);
            task.setRetryCount(0);
            task.setMaxRetryCount(properties.maxRetryCount());
            task.setNextExecuteTime(now);
            task.setCreatedTime(now);
            task.setUpdatedTime(now);
            try {
                taskMapper.insert(task);
            } catch (DuplicateKeyException ignored) {
                return taskMapper.selectByTaskNo(taskNo);
            }
            for (StepDefinition definition : steps) {
                TradeOrchestrationStep step = new TradeOrchestrationStep();
                step.setTaskNo(taskNo);
                step.setStepNo(definition.stepNo());
                step.setStepName(definition.stepName());
                step.setStepType(definition.stepType());
                step.setStatus(INIT);
                step.setIdempotencyKey(definition.idempotencyKey());
                step.setAttemptCount(0);
                step.setMaxAttemptCount(properties.maxRetryCount());
                step.setNextExecuteTime(now);
                step.setCreatedTime(now);
                step.setUpdatedTime(now);
                stepMapper.insert(step);
            }
            return task;
        });
        if (created != null && INIT.equals(created.getStatus())) {
            publisher.publishAfterCommit(created, properties.topic());
        }
        return created;
    }

    private StepDefinition step(int stepNo, String stepName, String stepType, String idempotencyKey) {
        return new StepDefinition(stepNo, stepName, stepType, idempotencyKey);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("编排上下文序列化失败", exception);
        }
    }

    private String truncate(String message) {
        if (message == null) {
            return "UNKNOWN_ERROR";
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }

    private String resolveWorkerId() {
        try {
            return InetAddress.getLocalHost().getHostName() + "-" + UUID.randomUUID();
        } catch (Exception exception) {
            return "worker-" + UUID.randomUUID();
        }
    }

    private record StepDefinition(
            int stepNo,
            String stepName,
            String stepType,
            String idempotencyKey
    ) {
    }
}
