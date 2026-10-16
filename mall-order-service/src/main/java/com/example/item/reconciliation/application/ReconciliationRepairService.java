package com.example.item.reconciliation.application;

import com.example.item.payment.application.PaymentConfirmationService;
import com.example.item.payment.channel.ChannelPaymentCommands.*;
import com.example.item.payment.channel.ChannelProfileSnapshot;
import com.example.item.payment.channel.PaymentChannelStrategy;
import com.example.item.payment.channel.PaymentStrategyRegistry;
import com.example.item.payment.domain.PaymentAttempt;
import com.example.item.payment.domain.PaymentMethod;
import com.example.item.payment.mapper.PaymentAttemptMapper;
import com.example.item.payment.mock.MockChannelEngine;
import com.example.item.reconciliation.domain.ReconDiff;
import com.example.item.reconciliation.domain.ReconDiffHistory;
import com.example.item.reconciliation.domain.ReconRepairCommand;
import com.example.item.reconciliation.mapper.ReconDiffHistoryMapper;
import com.example.item.reconciliation.mapper.ReconDiffMapper;
import com.example.item.reconciliation.mapper.ReconRepairCommandMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Service
@Slf4j
public class ReconciliationRepairService {

    private final ReconDiffMapper diffMapper;
    private final ReconDiffHistoryMapper diffHistoryMapper;
    private final ReconRepairCommandMapper commandMapper;
    private final PaymentAttemptMapper attemptMapper;
    private final PaymentStrategyRegistry strategyRegistry;
    private final PaymentConfirmationService confirmationService;
    private final ReconciliationExecutionService executionService;

    public ReconciliationRepairService(
            ReconDiffMapper diffMapper,
            ReconDiffHistoryMapper diffHistoryMapper,
            ReconRepairCommandMapper commandMapper,
            PaymentAttemptMapper attemptMapper,
            PaymentStrategyRegistry strategyRegistry,
            PaymentConfirmationService confirmationService,
            ReconciliationExecutionService executionService
    ) {
        this.diffMapper = diffMapper;
        this.diffHistoryMapper = diffHistoryMapper;
        this.commandMapper = commandMapper;
        this.attemptMapper = attemptMapper;
        this.strategyRegistry = strategyRegistry;
        this.confirmationService = confirmationService;
        this.executionService = executionService;
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> handleDiffAction(String diffNo, String action, String reason, String operator, String requestKey) {
        ReconDiff diff = diffMapper.selectByDiffNo(diffNo);
        if (diff == null) {
            throw new IllegalArgumentException("差异工单不存在: " + diffNo);
        }

        LocalDateTime now = LocalDateTime.now();
        String oldStatus = diff.getStatus();
        String commandNo = "CMD_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();

        ReconRepairCommand cmd = ReconRepairCommand.builder()
                .commandNo(commandNo)
                .diffNo(diffNo)
                .commandType(action)
                .requestKey(requestKey != null ? requestKey : commandNo)
                .payloadJson("{\"action\":\"" + action + "\",\"reason\":\"" + reason + "\"}")
                .status("EXECUTED")
                .operator(operator != null ? operator : "ADMIN")
                .createdTime(now)
                .updatedTime(now)
                .build();

        switch (action.toUpperCase()) {
            case "CLAIM" -> {
                diff.setStatus("INVESTIGATING");
                diff.setUpdatedTime(now);
                diffMapper.updateById(diff);
                recordHistory(diffNo, "CLAIM", oldStatus, "INVESTIGATING", operator, "认领排查", commandNo, now);
            }
            case "WAIVE" -> {
                if (reason == null || reason.isBlank()) {
                    throw new IllegalArgumentException("豁免差异必须提供原因说明");
                }
                diff.setStatus("WAIVED");
                diff.setWaiveReason(reason);
                diff.setResolvedAt(now);
                diff.setUpdatedTime(now);
                diffMapper.updateById(diff);
                recordHistory(diffNo, "WAIVE", oldStatus, "WAIVED", operator, reason, commandNo, now);
            }
            case "QUERY", "REPAIR" -> {
                diff.setStatus("REPAIRING");
                diff.setUpdatedTime(now);
                diffMapper.updateById(diff);

                // 如果是渠道单边支付，主动通过渠道查询并补全平台确认
                if ("CHANNEL_ONLY_PAYMENT".equals(diff.getDiffType()) && diff.getAttemptNo() != null) {
                    PaymentAttempt attempt = attemptMapper.selectByAttemptNo(diff.getAttemptNo());
                    if (attempt != null) {
                        PaymentMethod method = PaymentMethod.valueOf(attempt.getPaymentMethod());
                        PaymentChannelStrategy strategy = strategyRegistry.require(method);
                        ChannelProfileSnapshot profile = ChannelProfileSnapshot.defaultFor(method);

                        ChannelPaymentResult queryResult = strategy.queryPayment(new QueryChannelPayment(
                                attempt.getAttemptNo(), attempt.getOutTradeNo(), attempt.getChannelTradeNo(), profile));

                        if (queryResult.exists() && "SUCCESS".equals(queryResult.status())) {
                            VerifiedChannelEvent event = new VerifiedChannelEvent(
                                    "EVT_REPAIR_" + UUID.randomUUID().toString().replace("-", ""),
                                    profile.profileId(),
                                    profile.channelMerchantNo(),
                                    attempt.getAttemptNo(),
                                    null,
                                    queryResult.channelTradeNo(),
                                    "PAYMENT",
                                    queryResult.amount(),
                                    queryResult.feeAmount(),
                                    queryResult.currency(),
                                    queryResult.channelSuccessAt(),
                                    queryResult.channelBusinessDate(),
                                    queryResult.status(),
                                    MockChannelEngine.sha256(attempt.getAttemptNo()),
                                    "{}"
                            );
                            confirmationService.acceptVerifiedFact(event);
                            diff.setStatus("RESOLVED");
                            diff.setResolvedAt(now);
                            diffMapper.updateById(diff);
                            recordHistory(diffNo, "REPAIR_SUCCESS", oldStatus, "RESOLVED", operator,
                                    "主动查单并补入账/挂账成功", commandNo, now);
                        }
                    }
                }
            }
            case "RESOLVE" -> {
                diff.setStatus("RESOLVED");
                diff.setResolvedAt(now);
                diff.setUpdatedTime(now);
                diffMapper.updateById(diff);
                recordHistory(diffNo, "RESOLVE", oldStatus, "RESOLVED", operator, reason, commandNo, now);
            }
            default -> throw new IllegalArgumentException("不支持的差异处理动作: " + action);
        }

        commandMapper.insert(cmd);
        return Map.of("diffNo", diffNo, "action", action, "status", diff.getStatus(), "commandNo", commandNo);
    }

    private void recordHistory(String diffNo, String action, String fromStatus, String toStatus,
                               String operator, String evidence, String commandNo, LocalDateTime now) {
        ReconDiffHistory history = ReconDiffHistory.builder()
                .diffNo(diffNo)
                .action(action)
                .fromStatus(fromStatus)
                .toStatus(toStatus)
                .operator(operator != null ? operator : "ADMIN")
                .evidence(evidence)
                .commandNo(commandNo)
                .createdTime(now)
                .build();
        diffHistoryMapper.insert(history);
    }
}
