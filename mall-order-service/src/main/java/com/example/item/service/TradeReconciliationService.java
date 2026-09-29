package com.example.item.service;

import api.trade.FundDubboService;
import com.example.item.dto.ReconciliationDiffDTO;
import com.example.item.entity.TradeReconciliationDiff;
import com.example.item.mapper.TradeReconciliationDiffMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Service
@Slf4j
public class TradeReconciliationService {
    @DubboReference(timeout = 5000, retries = 0, check = false)
    private FundDubboService fundService;

    private final TradeReconciliationDiffMapper diffMapper;

    public TradeReconciliationService(TradeReconciliationDiffMapper diffMapper) {
        this.diffMapper = diffMapper;
    }

    public int reconcile() {
        int diffs = saveDiffs("ORDER_AMOUNT", diffMapper.selectOrderAmountDiffs());
        diffs += saveDiffs("SETTLEMENT", diffMapper.selectSettlementDiffs());
        diffs += reconcileEscrowAmount();
        if (diffs > 0) {
            log.warn("担保交易对账发现 {} 条差异，已记录待人工处理", diffs);
        }
        return diffs;
    }

    private int reconcileEscrowAmount() {
        BigDecimal expected = diffMapper.sumActiveEscrowAmount();
        BigDecimal actual = fundService.getPlatformEscrowAmount();
        return saveDiff("ESCROW_AMOUNT", "PLATFORM_MAIN", expected, actual);
    }

    private int saveDiffs(String diffType, List<ReconciliationDiffDTO> diffList) {
        int diffs = 0;
        for (ReconciliationDiffDTO diff : diffList) {
            diffs += saveDiff(diffType, diff.getBizNo(),
                    diff.getExpectedAmount(), diff.getActualAmount());
        }
        return diffs;
    }

    private int saveDiff(String diffType, String bizNo, BigDecimal expected, BigDecimal actual) {
        if (expected != null && actual != null && expected.compareTo(actual) == 0) {
            return 0;
        }
        TradeReconciliationDiff diff = new TradeReconciliationDiff();
        diff.setDiffKey(diffType + ":" + bizNo);
        diff.setDiffType(diffType);
        diff.setBizNo(bizNo);
        diff.setExpectedAmount(expected == null ? BigDecimal.ZERO : expected);
        diff.setActualAmount(actual == null ? BigDecimal.ZERO : actual);
        diff.setStatus("OPEN");
        diff.setCreatedTime(LocalDateTime.now());
        diffMapper.insertIgnore(diff);
        return 1;
    }
}
