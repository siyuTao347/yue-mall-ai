package com.example.item.reconciliation.statement;

import com.example.item.reconciliation.domain.ReconChannelItem;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Component
public class ReconciliationStatementParser {

    public List<ReconChannelItem> parse(Long fileId, String content) {
        return parseStatement(fileId, content);
    }

    public List<ReconChannelItem> parseStatement(Long fileId, String content) {
        List<ReconChannelItem> items = new ArrayList<>();
        if (content == null || content.isBlank()) {
            return items;
        }

        String[] lines = content.split("\\r?\\n");
        boolean headerPassed = false;

        for (String rawLine : lines) {
            String trimmed = rawLine.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            if (!headerPassed) {
                if (trimmed.startsWith("line_no")) {
                    headerPassed = true;
                }
                continue;
            }

            String[] cols = trimmed.split(",", -1);
            if (cols.length < 9) {
                continue;
            }

            try {
                int lineNo = Integer.parseInt(cols[0].trim());
                String itemType = cols[1].trim();
                String outTradeNo = cols[2].trim();
                String channelTradeNo = cols[3].trim();
                String refundNo = cols[4].trim();
                BigDecimal amount = new BigDecimal(cols[5].trim());
                BigDecimal fee = cols[6].trim().isEmpty() ? BigDecimal.ZERO : new BigDecimal(cols[6].trim());
                String currency = cols[7].trim();
                String status = cols[8].trim();
                LocalDateTime channelTime = cols.length > 9 && !cols[9].trim().isEmpty()
                        ? LocalDateTime.parse(cols[9].trim()) : LocalDateTime.now();

                ReconChannelItem item = ReconChannelItem.builder()
                        .fileId(fileId)
                        .lineNo(lineNo)
                        .itemType(itemType)
                        .outTradeNo(outTradeNo.isEmpty() ? null : outTradeNo)
                        .channelTradeNo(channelTradeNo.isEmpty() ? null : channelTradeNo)
                        .refundNo(refundNo.isEmpty() ? null : refundNo)
                        .amount(amount)
                        .feeAmount(fee)
                        .currency(currency)
                        .status(status)
                        .channelTime(channelTime)
                        .rawLine(trimmed)
                        .createdTime(LocalDateTime.now())
                        .build();
                items.add(item);
            } catch (Exception e) {
                // Ignore broken line or record as unparsed
            }
        }
        return items;
    }
}
