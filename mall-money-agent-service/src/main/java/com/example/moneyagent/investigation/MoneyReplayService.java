package com.example.moneyagent.investigation;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.moneyagent.metrics.MoneyOperationalMetrics;
import com.example.moneyagent.persistence.entity.MoneyCaseEntity;
import com.example.moneyagent.persistence.entity.MoneyReportEntity;
import com.example.moneyagent.persistence.entity.MoneySnapshotEntity;
import com.example.moneyagent.persistence.entity.MoneyTaskEntity;
import com.example.moneyagent.persistence.entity.MoneyToolCallEntity;
import com.example.moneyagent.persistence.mapper.MoneyCaseMapper;
import com.example.moneyagent.persistence.mapper.MoneyReportMapper;
import com.example.moneyagent.persistence.mapper.MoneySnapshotMapper;
import com.example.moneyagent.persistence.mapper.MoneyTaskMapper;
import com.example.moneyagent.persistence.mapper.MoneyToolCallMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class MoneyReplayService {

    private final MoneyTaskMapper taskMapper;
    private final MoneyCaseMapper caseMapper;
    private final MoneyReportMapper reportMapper;
    private final MoneySnapshotMapper snapshotMapper;
    private final MoneyToolCallMapper toolCallMapper;
    private final MoneyOperationalMetrics metrics;
    private final ObjectMapper objectMapper;

    public MoneyReplayService(MoneyTaskMapper taskMapper,
                              MoneyCaseMapper caseMapper,
                              MoneyReportMapper reportMapper,
                              MoneySnapshotMapper snapshotMapper,
                              MoneyToolCallMapper toolCallMapper,
                              MoneyOperationalMetrics metrics,
                              ObjectMapper objectMapper) {
        this.taskMapper = taskMapper;
        this.caseMapper = caseMapper;
        this.reportMapper = reportMapper;
        this.snapshotMapper = snapshotMapper;
        this.toolCallMapper = toolCallMapper;
        this.metrics = metrics != null ? metrics : new MoneyOperationalMetrics();
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    public MoneyReplayResult replayInvestigation(String taskNo) {
        LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC);
        if (taskNo == null || taskNo.isBlank()) {
            throw new IllegalArgumentException("taskNo 不能为空");
        }

        MoneyTaskEntity task = taskMapper.selectOne(new LambdaQueryWrapper<MoneyTaskEntity>()
                .eq(MoneyTaskEntity::getTaskNo, taskNo));
        if (task == null) {
            throw new IllegalArgumentException("任务不存在: " + taskNo);
        }

        MoneyCaseEntity caseEntity = caseMapper.selectOne(new LambdaQueryWrapper<MoneyCaseEntity>()
                .eq(MoneyCaseEntity::getCaseNo, task.getCaseNo()));
        if (caseEntity == null) {
            throw new IllegalArgumentException("关联案件不存在: " + task.getCaseNo());
        }

        List<MoneySnapshotEntity> snapshots = snapshotMapper.selectList(new LambdaQueryWrapper<MoneySnapshotEntity>()
                .eq(MoneySnapshotEntity::getTaskNo, taskNo)
                .orderByAsc(MoneySnapshotEntity::getStepNo));

        List<MoneyToolCallEntity> toolCalls = toolCallMapper.selectList(new LambdaQueryWrapper<MoneyToolCallEntity>()
                .eq(MoneyToolCallEntity::getTaskNo, taskNo)
                .orderByAsc(MoneyToolCallEntity::getCallSeq));

        MoneyReportEntity report = reportMapper.selectOne(new LambdaQueryWrapper<MoneyReportEntity>()
                .eq(MoneyReportEntity::getTaskNo, taskNo)
                .orderByDesc(MoneyReportEntity::getRevision)
                .last("LIMIT 1"));

        String originalOutcome = report != null ? report.getOutcome() : task.getState();

        // 仅根据历史不可变快照事实进行纯净规则回放（绝对禁止外部副作用/写调用）
        List<Map<String, Object>> replayedFindings = new ArrayList<>();
        String replayedOutcome = evaluateReplayedRules(caseEntity, snapshots, replayedFindings);

        boolean match = originalOutcome != null && originalOutcome.equalsIgnoreCase(replayedOutcome);
        metrics.recordReplayExecuted(match);

        log.info("MoneyReplay 执行完成, taskNo={}, originalOutcome={}, replayedOutcome={}, match={}",
                taskNo, originalOutcome, replayedOutcome, match);

        return MoneyReplayResult.builder()
                .taskNo(taskNo)
                .caseNo(caseEntity.getCaseNo())
                .problemFamily(caseEntity.getProblemFamily())
                .originalOutcome(originalOutcome)
                .replayedOutcome(replayedOutcome)
                .match(match)
                .snapshotCount(snapshots != null ? snapshots.size() : 0)
                .toolCallCount(toolCalls != null ? toolCalls.size() : 0)
                .evidenceFingerprint(report != null ? report.getBasedOnFingerprint() : null)
                .replayedFindings(replayedFindings)
                .replayedSummary("回放评估完成: " + replayedOutcome)
                .replayedAt(nowUtc)
                .build();
    }

    private String evaluateReplayedRules(MoneyCaseEntity caseEntity,
                                        List<MoneySnapshotEntity> snapshots,
                                        List<Map<String, Object>> findings) {
        if (snapshots == null || snapshots.isEmpty()) {
            findings.add(Map.of("code", "NO_SNAPSHOT_FACTS", "message", "无不可变历史快照"));
            return "INCONCLUSIVE";
        }

        // 解析聚合快照事实
        boolean hasOrder = false;
        boolean hasFund = false;
        boolean hasChannel = false;
        String channelStatus = null;

        for (MoneySnapshotEntity snap : snapshots) {
            String kind = snap.getSourceKind();
            if (kind != null && kind.contains("ORDER")) {
                hasOrder = true;
            } else if (kind != null && kind.contains("FUND")) {
                hasFund = true;
            } else if (kind != null && kind.contains("CHANNEL")) {
                hasChannel = true;
                if (snap.getFactsJson() != null && (snap.getFactsJson().contains("\"status\":\"SUCCESS\"")
                        || snap.getFactsJson().contains("\"rawStatus\":\"SUCCESS\"")
                        || snap.getFactsJson().contains("\"channelStatus\":\"SUCCESS\""))) {
                    channelStatus = "SUCCESS";
                }
            }
        }

        String problem = caseEntity.getProblemFamily();
        if ("PAYMENT_CONFIRMATION".equalsIgnoreCase(problem)) {
            if ("SUCCESS".equalsIgnoreCase(channelStatus)) {
                findings.add(Map.of("code", "PAYMENT_CONFIRMED", "message", "外部支付渠道确认成功"));
                return "VERIFIED_NORMAL";
            } else if (hasChannel) {
                return "VERIFIED_NORMAL";
            }
        } else if ("REFUND_STATUS".equalsIgnoreCase(problem)) {
            if ("SUCCESS".equalsIgnoreCase(channelStatus)) {
                findings.add(Map.of("code", "REFUND_CONFIRMED", "message", "外部退款渠道确认到账"));
                return "VERIFIED_RESOLVED";
            }
        }

        return "VERIFIED_NORMAL";
    }
}
