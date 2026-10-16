package com.example.item.reconciliation.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.item.payment.channel.ChannelProfileSnapshot;
import com.example.item.payment.domain.PaymentFinancialEvent;
import com.example.item.payment.domain.PaymentMethod;
import com.example.item.payment.mapper.PaymentFinancialEventMapper;
import com.example.item.payment.mock.MockChannelEngine;
import com.example.item.payment.mock.MockDailyStatementService;
import com.example.item.payment.mock.MockStatement;
import com.example.item.reconciliation.domain.*;
import com.example.item.reconciliation.mapper.*;
import com.example.item.reconciliation.statement.ReconciliationStatementParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

@Service
@Slf4j
public class ReconciliationExecutionService {

    private final ReconTaskMapper taskMapper;
    private final ReconRunMapper runMapper;
    private final ReconFileMapper fileMapper;
    private final ReconChannelItemMapper channelItemMapper;
    private final ReconLocalItemMapper localItemMapper;
    private final ReconMatchMapper matchMapper;
    private final ReconDiffMapper diffMapper;
    private final ReconDiffHistoryMapper diffHistoryMapper;
    private final PaymentFinancialEventMapper financialEventMapper;
    private final MockDailyStatementService statementService;
    private final ReconciliationStatementParser statementParser;

    public ReconciliationExecutionService(
            ReconTaskMapper taskMapper,
            ReconRunMapper runMapper,
            ReconFileMapper fileMapper,
            ReconChannelItemMapper channelItemMapper,
            ReconLocalItemMapper localItemMapper,
            ReconMatchMapper matchMapper,
            ReconDiffMapper diffMapper,
            ReconDiffHistoryMapper diffHistoryMapper,
            PaymentFinancialEventMapper financialEventMapper,
            MockDailyStatementService statementService,
            ReconciliationStatementParser statementParser
    ) {
        this.taskMapper = taskMapper;
        this.runMapper = runMapper;
        this.fileMapper = fileMapper;
        this.channelItemMapper = channelItemMapper;
        this.localItemMapper = localItemMapper;
        this.matchMapper = matchMapper;
        this.diffMapper = diffMapper;
        this.diffHistoryMapper = diffHistoryMapper;
        this.financialEventMapper = financialEventMapper;
        this.statementService = statementService;
        this.statementParser = statementParser;
    }

    @Transactional(rollbackFor = Exception.class)
    public ReconRun executeReconciliation(PaymentMethod method, String statementType, LocalDate businessDate, String mode) {
        ChannelProfileSnapshot profile = ChannelProfileSnapshot.defaultFor(method);
        String profileId = profile.profileId();
        String merchantNo = profile.channelMerchantNo();
        LocalDateTime now = LocalDateTime.now();

        // 1. 获取或创建对账任务
        ReconTask task = taskMapper.findTask(profileId, businessDate, statementType);
        if (task == null) {
            String taskNo = "TASK_" + profileId + "_" + businessDate.toString().replace("-", "") + "_" + statementType;
            task = ReconTask.builder()
                    .taskNo(taskNo)
                    .scopeId("DEFAULT")
                    .profileId(profileId)
                    .channelMerchantNo(merchantNo)
                    .accountId(profile.accountId())
                    .statementType(statementType)
                    .businessDate(businessDate)
                    .mode(mode != null ? mode : "DAILY")
                    .windowEnd(LocalDateTime.of(businessDate.plusDays(1), java.time.LocalTime.MIDNIGHT))
                    .taskStatus("RUNNING")
                    .createdTime(now)
                    .updatedTime(now)
                    .build();
            taskMapper.insert(task);
        }

        // 2. 创建本次 Run
        int runSeq = runMapper.getMaxRunSeq(task.getTaskNo()) + 1;
        String runNo = "RUN_" + UUID.randomUUID().toString().replace("-", "").substring(0, 18).toUpperCase();
        ReconRun run = ReconRun.builder()
                .runNo(runNo)
                .taskNo(task.getTaskNo())
                .runSeq(runSeq)
                .snapshotCutoff(now)
                .ruleVersion(1)
                .executionStatus("RUNNING")
                .outcome("PENDING")
                .startedAt(now)
                .createdTime(now)
                .build();
        runMapper.insert(run);

        // 3. 构建/获取渠道模拟账单
        MockStatement statement = statementService.buildDailyTradeStatement(method, businessDate);
        String fileNo = "FILE_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
        ReconFile reconFile = ReconFile.builder()
                .fileNo(fileNo)
                .profileId(profileId)
                .channelMerchantNo(merchantNo)
                .statementType(statementType)
                .businessDate(businessDate)
                .version(statement.getVersion())
                .checksum(statement.getFileHash())
                .parserVersion("v1")
                .fileLocation(statement.getFileLocation())
                .rawContent(statement.getFileContent())
                .rowCount(statement.getRowCount())
                .status("PARSED")
                .createdTime(now)
                .build();
        fileMapper.insert(reconFile);

        run.setFileId(reconFile.getId());
        run.setFileHash(statement.getFileHash());

        // 4. 解析渠道条目并持久化
        List<ReconChannelItem> channelItems = statementParser.parse(reconFile.getId(), statement.getFileContent());
        for (ReconChannelItem item : channelItems) {
            channelItemMapper.insert(item);
        }

        // 5. 提取平台金融事件快照并持久化
        List<PaymentFinancialEvent> localEvents = financialEventMapper.selectForReconciliation(
                profileId, merchantNo, businessDate, now);
        List<ReconLocalItem> localItems = new ArrayList<>();
        for (PaymentFinancialEvent fe : localEvents) {
            ReconLocalItem li = ReconLocalItem.builder()
                    .runNo(runNo)
                    .financialEventNo(fe.getEventNo())
                    .itemType(fe.getEventType())
                    .bizNo(fe.getBizNo())
                    .orderNo(fe.getOrderNo())
                    .amount(fe.getAmount())
                    .feeAmount(fe.getFeeAmount())
                    .currency(fe.getCurrency())
                    .status(fe.getEventStage())
                    .occurredAt(fe.getOccurredAt())
                    .createdTime(now)
                    .build();
            localItemMapper.insert(li);
            localItems.add(li);
        }

        // 6. 双向比对
        // 建立索引：渠道按 outTradeNo/refundNo，平台按 bizNo
        Map<String, ReconChannelItem> channelMap = new HashMap<>();
        for (ReconChannelItem ci : channelItems) {
            String key = "PAYMENT".equals(ci.getItemType()) ? ci.getOutTradeNo() : ci.getRefundNo();
            if (key != null && !key.isBlank()) {
                channelMap.put(key, ci);
            }
        }

        int matchedCount = 0;
        int diffCount = 0;
        BigDecimal channelAmountTotal = BigDecimal.ZERO;
        BigDecimal localAmountTotal = BigDecimal.ZERO;
        BigDecimal matchedAmountTotal = BigDecimal.ZERO;
        BigDecimal diffAmountTotal = BigDecimal.ZERO;

        for (ReconChannelItem ci : channelItems) {
            channelAmountTotal = channelAmountTotal.add(ci.getAmount());
        }
        for (ReconLocalItem li : localItems) {
            localAmountTotal = localAmountTotal.add(li.getAmount());
        }

        Set<String> matchedChannelKeys = new HashSet<>();

        // 6.1 遍历平台快照对比渠道
        for (ReconLocalItem li : localItems) {
            String bizKey = li.getBizNo();
            ReconChannelItem ci = channelMap.get(bizKey);

            if (ci != null) {
                matchedChannelKeys.add(bizKey);
                if (ci.getAmount().compareTo(li.getAmount()) == 0) {
                    // 金额完全一致
                    matchedCount++;
                    matchedAmountTotal = matchedAmountTotal.add(li.getAmount());
                    saveMatch(runNo, "L1_TRADE", ci.getId(), li.getId(), bizKey, ci.getAmount(), li.getAmount(), "MATCHED");
                } else {
                    // 金额不一致差异
                    diffCount++;
                    diffAmountTotal = diffAmountTotal.add(li.getAmount().subtract(ci.getAmount()).abs());
                    saveMatch(runNo, "L1_TRADE", ci.getId(), li.getId(), bizKey, ci.getAmount(), li.getAmount(), "AMOUNT_DIFF");
                    recordDiff(profileId, merchantNo, profile.accountId(), "L1", "AMOUNT_MISMATCH", "HIGH",
                            bizKey, li.getOrderNo(), bizKey, null, ci.getAmount(), li.getAmount(),
                            ci.getStatus(), li.getStatus(), runNo, now);
                }
            } else {
                // 平台单边账（平台有，完整渠道账没有）
                diffCount++;
                diffAmountTotal = diffAmountTotal.add(li.getAmount());
                saveMatch(runNo, "L1_TRADE", null, li.getId(), bizKey, null, li.getAmount(), "RIGHT_ONLY");
                recordDiff(profileId, merchantNo, profile.accountId(), "L1", "PLATFORM_ONLY_PAYMENT", "HIGH",
                        bizKey, li.getOrderNo(), bizKey, null, null, li.getAmount(),
                        null, li.getStatus(), runNo, now);
            }
        }

        // 6.2 遍历剩下的渠道项（渠道单边账）
        for (Map.Entry<String, ReconChannelItem> entry : channelMap.entrySet()) {
            String bizKey = entry.getKey();
            if (!matchedChannelKeys.contains(bizKey)) {
                ReconChannelItem ci = entry.getValue();
                diffCount++;
                diffAmountTotal = diffAmountTotal.add(ci.getAmount());
                saveMatch(runNo, "L1_TRADE", ci.getId(), null, bizKey, ci.getAmount(), null, "LEFT_ONLY");
                recordDiff(profileId, merchantNo, profile.accountId(), "L1", "CHANNEL_ONLY_PAYMENT", "HIGH",
                        bizKey, null, ci.getOutTradeNo(), ci.getRefundNo(), ci.getAmount(), null,
                        ci.getStatus(), null, runNo, now);
            }
        }

        // 7. 更新 Run 结果
        run.setChannelCount(channelItems.size());
        run.setLocalCount(localItems.size());
        run.setMatchedCount(matchedCount);
        run.setDiffCount(diffCount);
        run.setChannelAmount(channelAmountTotal);
        run.setLocalAmount(localAmountTotal);
        run.setMatchedAmount(matchedAmountTotal);
        run.setDiffAmount(diffAmountTotal);
        run.setExecutionStatus("COMPLETED");
        run.setOutcome(diffCount == 0 ? "BALANCED" : "DIFF_FOUND");
        run.setCompletedAt(LocalDateTime.now());
        runMapper.updateById(run);

        // 8. 更新 Task 状态
        task.setTaskStatus("COMPLETED");
        task.setUpdatedTime(LocalDateTime.now());
        taskMapper.updateById(task);

        log.info("Reconciliation run completed: runNo={}, outcome={}, matched={}, diffs={}",
                runNo, run.getOutcome(), matchedCount, diffCount);

        return run;
    }

    private void saveMatch(String runNo, String matchType, Long channelItemId, Long localItemId,
                           String bizKey, BigDecimal channelAmount, BigDecimal localAmount, String matchStatus) {
        ReconMatch match = ReconMatch.builder()
                .runNo(runNo)
                .matchType(matchType)
                .channelItemId(channelItemId)
                .localItemId(localItemId)
                .bizKey(bizKey)
                .channelAmount(channelAmount)
                .localAmount(localAmount)
                .matchStatus(matchStatus)
                .ruleVersion(1)
                .createdTime(LocalDateTime.now())
                .build();
        matchMapper.insert(match);
    }

    private void recordDiff(String profileId, String merchantNo, String accountId, String reconLayer,
                            String diffType, String severity, String bizKey, String orderNo,
                            String attemptNo, String refundNo, BigDecimal channelAmount, BigDecimal localAmount,
                            String channelStatus, String localStatus, String runNo, LocalDateTime now) {
        String diffKey = profileId + ":" + merchantNo + ":" + reconLayer + ":" + bizKey + ":" + diffType;
        ReconDiff existing = diffMapper.selectByDiffKey(diffKey);

        if (existing != null) {
            // 更新最后检出轮次与时间
            existing.setLastSeenRunNo(runNo);
            existing.setLastSeenAt(now);
            if ("RESOLVED".equals(existing.getStatus()) || "WAIVED".equals(existing.getStatus())) {
                existing.setStatus("REOPENED");
            }
            existing.setChannelAmount(channelAmount);
            existing.setLocalAmount(localAmount);
            existing.setChannelStatus(channelStatus);
            existing.setLocalStatus(localStatus);
            existing.setUpdatedTime(now);
            diffMapper.updateById(existing);

            ReconDiffHistory history = ReconDiffHistory.builder()
                    .diffNo(existing.getDiffNo())
                    .action("DETECTED_AGAIN")
                    .fromStatus(existing.getStatus())
                    .toStatus(existing.getStatus())
                    .operator("SYSTEM_RECON")
                    .evidence("Run: " + runNo)
                    .createdTime(now)
                    .build();
            diffHistoryMapper.insert(history);
        } else {
            String diffNo = "DIFF_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
            ReconDiff diff = ReconDiff.builder()
                    .diffNo(diffNo)
                    .diffKey(diffKey)
                    .profileId(profileId)
                    .channelMerchantNo(merchantNo)
                    .accountId(accountId)
                    .reconLayer(reconLayer)
                    .diffType(diffType)
                    .severity(severity)
                    .bizKey(bizKey)
                    .orderNo(orderNo)
                    .attemptNo(attemptNo)
                    .refundNo(refundNo)
                    .channelAmount(channelAmount)
                    .localAmount(localAmount)
                    .channelStatus(channelStatus)
                    .localStatus(localStatus)
                    .firstSeenRunNo(runNo)
                    .lastSeenRunNo(runNo)
                    .status("OPEN")
                    .firstSeenAt(now)
                    .lastSeenAt(now)
                    .createdTime(now)
                    .updatedTime(now)
                    .build();
            diffMapper.insert(diff);

            ReconDiffHistory history = ReconDiffHistory.builder()
                    .diffNo(diffNo)
                    .action("DETECTED")
                    .fromStatus("NONE")
                    .toStatus("OPEN")
                    .operator("SYSTEM_RECON")
                    .evidence("Run: " + runNo)
                    .createdTime(now)
                    .build();
            diffHistoryMapper.insert(history);
        }
    }
}
