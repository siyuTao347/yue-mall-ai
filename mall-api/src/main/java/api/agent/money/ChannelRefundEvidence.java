package api.agent.money;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 退款渠道事实观察只读凭据
 */
public record ChannelRefundEvidence(
        ChannelEvidenceExistence existence,
        String rawStatus,
        String merchantNo,
        String refundNo,
        String channelRefundNo,
        BigDecimal amount,
        String currency,
        String occurredAt,
        String observedAt,
        String provider,
        String environment,
        FieldMatchQuality merchantMatchQuality,
        FieldMatchQuality amountMatchQuality,
        FieldMatchQuality currencyMatchQuality
) implements Serializable {}

