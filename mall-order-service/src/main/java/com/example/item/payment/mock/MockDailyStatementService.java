package com.example.item.payment.mock;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.item.payment.channel.ChannelProfileSnapshot;
import com.example.item.payment.domain.PaymentMethod;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

@Service
@Slf4j
public class MockDailyStatementService {

    private final MockChannelTransactionMapper transactionMapper;
    private final MockChannelRefundMapper refundMapper;
    private final MockStatementMapper statementMapper;

    public MockDailyStatementService(
            MockChannelTransactionMapper transactionMapper,
            MockChannelRefundMapper refundMapper,
            MockStatementMapper statementMapper
    ) {
        this.transactionMapper = transactionMapper;
        this.refundMapper = refundMapper;
        this.statementMapper = statementMapper;
    }

    public MockStatement buildDailyTradeStatement(PaymentMethod method, LocalDate businessDate) {
        ChannelProfileSnapshot profile = ChannelProfileSnapshot.defaultFor(method);
        String profileId = profile.profileId();
        String merchantNo = profile.channelMerchantNo();

        // 检查是否已有对应版本的账单
        MockStatement existing = statementMapper.findLatest(profileId, "TRADE", businessDate);
        if (existing != null) {
            return existing;
        }

        List<MockChannelTransaction> txList = transactionMapper.listByBusinessDate(profileId, merchantNo, businessDate);
        List<MockChannelRefund> refList = refundMapper.listByBusinessDate(merchantNo, businessDate);

        BigDecimal totalPay = BigDecimal.ZERO;
        BigDecimal totalPayFee = BigDecimal.ZERO;
        for (MockChannelTransaction tx : txList) {
            if ("SUCCESS".equals(tx.getStatus())) {
                totalPay = totalPay.add(tx.getAmount());
                totalPayFee = totalPayFee.add(tx.getFeeAmount() != null ? tx.getFeeAmount() : BigDecimal.ZERO);
            }
        }

        BigDecimal totalRefund = BigDecimal.ZERO;
        BigDecimal totalRefundFee = BigDecimal.ZERO;
        for (MockChannelRefund ref : refList) {
            if ("SUCCEEDED".equals(ref.getStatus())) {
                totalRefund = totalRefund.add(ref.getAmount());
                totalRefundFee = totalRefundFee.add(ref.getFeeReturnAmount() != null ? ref.getFeeReturnAmount() : BigDecimal.ZERO);
            }
        }

        BigDecimal netAmount = totalPay.subtract(totalRefund).subtract(totalPayFee).add(totalRefundFee);

        StringBuilder sb = new StringBuilder();
        sb.append("# STATEMENT_TYPE: TRADE\n");
        sb.append("# PROFILE_ID: ").append(profileId).append("\n");
        sb.append("# MERCHANT_NO: ").append(merchantNo).append("\n");
        sb.append("# BUSINESS_DATE: ").append(businessDate).append("\n");
        sb.append("line_no,item_type,out_trade_no,channel_trade_no,refund_no,amount,fee_amount,currency,status,time\n");

        int lineNo = 1;
        for (MockChannelTransaction tx : txList) {
            sb.append(lineNo++).append(",")
                    .append("PAYMENT").append(",")
                    .append(tx.getOutTradeNo()).append(",")
                    .append(tx.getChannelTradeNo()).append(",")
                    .append("").append(",")
                    .append(tx.getAmount().toPlainString()).append(",")
                    .append(tx.getFeeAmount() != null ? tx.getFeeAmount().toPlainString() : "0.00").append(",")
                    .append(tx.getCurrency()).append(",")
                    .append(tx.getStatus()).append(",")
                    .append(tx.getChannelSuccessAt()).append("\n");
        }

        for (MockChannelRefund ref : refList) {
            sb.append(lineNo++).append(",")
                    .append("REFUND").append(",")
                    .append("").append(",")
                    .append(ref.getChannelTradeNo()).append(",")
                    .append(ref.getRefundNo()).append(",")
                    .append(ref.getAmount().toPlainString()).append(",")
                    .append(ref.getFeeReturnAmount() != null ? ref.getFeeReturnAmount().toPlainString() : "0.00").append(",")
                    .append(ref.getCurrency()).append(",")
                    .append(ref.getStatus()).append(",")
                    .append(ref.getSuccessAt() != null ? ref.getSuccessAt() : ref.getAcceptedAt()).append("\n");
        }

        String content = sb.toString();
        String fileHash = MockChannelEngine.sha256(content);
        LocalDateTime now = LocalDateTime.now();
        LocalTime availTime = LocalTime.parse(profile.statementAvailableTime());
        LocalDateTime availableAt = LocalDateTime.of(businessDate.plusDays(1), availTime);

        MockStatement statement = MockStatement.builder()
                .scopeId("DEFAULT")
                .profileId(profileId)
                .channelMerchantNo(merchantNo)
                .accountId(profile.accountId())
                .statementType("TRADE")
                .businessDate(businessDate)
                .version(1)
                .fileHash(fileHash)
                .rowCount(txList.size() + refList.size())
                .totalPayAmount(totalPay)
                .totalRefundAmount(totalRefund)
                .totalFeeAmount(totalPayFee)
                .totalFeeReturnAmount(totalRefundFee)
                .netAmount(netAmount)
                .availableAt(availableAt)
                .generatedAt(now)
                .fileLocation("DB_INLINE")
                .fileContent(content)
                .createdTime(now)
                .build();

        try {
            statementMapper.insert(statement);
        } catch (Exception e) {
            log.warn("MockStatement insertion duplicate or failed: {}", e.getMessage());
            return statementMapper.findLatest(profileId, "TRADE", businessDate);
        }

        return statement;
    }
}
