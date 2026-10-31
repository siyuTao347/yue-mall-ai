package com.example.item.money;

import api.agent.money.*;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.item.reconciliation.domain.*;
import com.example.item.reconciliation.mapper.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class MoneyReconEvidenceService {

    private final ReconDiffMapper diffMapper;
    private final ReconRunMapper runMapper;
    private final ReconMatchMapper matchMapper;
    private final ReconLocalItemMapper localItemMapper;
    private final ReconChannelItemMapper channelItemMapper;
    private final ReconTaskMapper taskMapper;
    private final ReconFileMapper fileMapper;

    public MoneyReconEvidenceService(ReconDiffMapper diffMapper,
                                     ReconRunMapper runMapper,
                                     ReconMatchMapper matchMapper,
                                     ReconLocalItemMapper localItemMapper,
                                     ReconChannelItemMapper channelItemMapper,
                                     @Autowired(required = false) ReconTaskMapper taskMapper,
                                     @Autowired(required = false) ReconFileMapper fileMapper) {
        this.diffMapper = diffMapper;
        this.runMapper = runMapper;
        this.matchMapper = matchMapper;
        this.localItemMapper = localItemMapper;
        this.channelItemMapper = channelItemMapper;
        this.taskMapper = taskMapper;
        this.fileMapper = fileMapper;
    }

    public MoneyReconEvidence getEvidence(MoneyAccessContext access, MoneyReconQuery query) {
        if (query == null || query.getDiffNo() == null || query.getDiffNo().isBlank()) {
            return MoneyReconEvidence.notFound("EMPTY_DIFF_NO");
        }

        ReconDiff diff = diffMapper.selectOne(new LambdaQueryWrapper<ReconDiff>()
                .eq(ReconDiff::getDiffNo, query.getDiffNo().trim()));
        if (diff == null) {
            return MoneyReconEvidence.notFound(query.getDiffNo());
        }

        String runNo = diff.getLastSeenRunNo() != null ? diff.getLastSeenRunNo() : diff.getFirstSeenRunNo();
        ReconRun run = runNo != null ? runMapper.selectOne(new LambdaQueryWrapper<ReconRun>().eq(ReconRun::getRunNo, runNo)) : null;

        // R10 修复：从 diff -> run -> task / file 完整还原任务窗口与对账文件状态
        ReconTask task = (run != null && run.getTaskNo() != null && taskMapper != null)
                ? taskMapper.selectOne(new LambdaQueryWrapper<ReconTask>().eq(ReconTask::getTaskNo, run.getTaskNo()))
                : null;

        ReconFile file = (run != null && run.getFileId() != null && fileMapper != null)
                ? fileMapper.selectById(run.getFileId())
                : null;

        boolean windowClosed = run != null &&
                ("SUCCESS".equalsIgnoreCase(run.getExecutionStatus()) || "COMPLETED".equalsIgnoreCase(run.getExecutionStatus()))
                && (task == null || task.getWindowEnd() == null || LocalDateTime.now().isAfter(task.getWindowEnd()))
                && (file == null || "PARSED".equalsIgnoreCase(file.getStatus()));

        ReconMatch match = null;
        if (runNo != null && diff.getBizKey() != null) {
            match = matchMapper.selectOne(new LambdaQueryWrapper<ReconMatch>()
                    .eq(ReconMatch::getRunNo, runNo)
                    .eq(ReconMatch::getBizKey, diff.getBizKey()));
        }

        ReconLocalItem localItem = null;
        if (match != null && match.getLocalItemId() != null) {
            localItem = localItemMapper.selectById(match.getLocalItemId());
        } else if (runNo != null && diff.getBizKey() != null) {
            localItem = localItemMapper.selectOne(new LambdaQueryWrapper<ReconLocalItem>()
                    .eq(ReconLocalItem::getRunNo, runNo)
                    .eq(ReconLocalItem::getBizNo, diff.getBizKey()));
        }

        ReconChannelItem channelItem = null;
        boolean ambiguousChannelItem = false;
        if (match != null && match.getChannelItemId() != null) {
            channelItem = channelItemMapper.selectById(match.getChannelItemId());
        } else if (diff.getBizKey() != null) {
            // R10 修复：限定 run 的 fileId，避免命中历史重跑或旧账单条目；多条匹配时不任取一行，报告歧义
            LambdaQueryWrapper<ReconChannelItem> queryWrapper = new LambdaQueryWrapper<ReconChannelItem>()
                    .and(q -> q.eq(ReconChannelItem::getOutTradeNo, diff.getBizKey())
                            .or()
                            .eq(ReconChannelItem::getRefundNo, diff.getBizKey()));
            if (run != null && run.getFileId() != null) {
                queryWrapper.eq(ReconChannelItem::getFileId, run.getFileId());
            }
            List<ReconChannelItem> items = channelItemMapper.selectList(queryWrapper);
            if (items != null) {
                if (items.size() == 1) {
                    channelItem = items.get(0);
                } else if (items.size() > 1) {
                    ambiguousChannelItem = true;
                }
            }
        }

        // R10 修复：读取渠道真实原始 itemType，单边退款不粗暴默认为 PAYMENT
        String resolvedItemType = "PAYMENT";
        if (channelItem != null && channelItem.getItemType() != null && !channelItem.getItemType().isBlank()) {
            resolvedItemType = channelItem.getItemType().toUpperCase();
        } else if (localItem != null && localItem.getItemType() != null && !localItem.getItemType().isBlank()) {
            resolvedItemType = localItem.getItemType().toUpperCase();
        } else if (diff.getBizKey() != null && (diff.getBizKey().startsWith("RF_") || diff.getBizKey().contains("REFUND"))) {
            resolvedItemType = "REFUND";
        }

        Map<String, String> sources = new HashMap<>();
        sources.put("RECON_DIFF", MoneyCoverageStatus.OK.name());
        sources.put("LOCAL_ITEM", localItem != null ? MoneyCoverageStatus.OK.name() : MoneyCoverageStatus.NOT_FOUND.name());
        sources.put("CHANNEL_ITEM", ambiguousChannelItem ? "AMBIGUOUS"
                : (channelItem != null ? MoneyCoverageStatus.OK.name() : MoneyCoverageStatus.NOT_FOUND.name()));
        CoverageInfo coverage = new CoverageInfo(sources, !ambiguousChannelItem, false, null, ambiguousChannelItem ? "AMBIGUOUS_CHANNEL" : "Normal");

        // R10 修复：真实业务日优先从 task 读取，重跑时不被执行时间掩盖
        String billDate = null;
        if (task != null && task.getBusinessDate() != null) {
            billDate = task.getBusinessDate().toString();
        } else if (run != null && run.getSnapshotCutoff() != null) {
            billDate = run.getSnapshotCutoff().toLocalDate().toString();
        } else if (diff.getLastSeenAt() != null) {
            billDate = diff.getLastSeenAt().toLocalDate().toString();
        }

        return new MoneyReconEvidence(
                diff.getDiffNo(),
                diff.getDiffType(),
                diff.getBizKey(),
                diff.getOrderNo(),
                diff.getAttemptNo(),
                diff.getRefundNo(),
                resolvedItemType,
                runNo,
                billDate,
                windowClosed,
                channelItem != null ? String.valueOf(channelItem.getId()) : null,
                channelItem != null ? channelItem.getAmount() : diff.getChannelAmount(),
                channelItem != null ? channelItem.getStatus() : diff.getChannelStatus(),
                localItem != null ? String.valueOf(localItem.getId()) : null,
                localItem != null ? localItem.getAmount() : diff.getLocalAmount(),
                localItem != null ? localItem.getStatus() : diff.getLocalStatus(),
                coverage,
                Instant.now().toString(),
                windowClosed ? "Reconciliation window closed" : "Reconciliation window still open"
        );
    }
}
