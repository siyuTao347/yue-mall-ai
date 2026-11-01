package api.agent.money;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 支付渠道事实观察只读凭据
 */
public record ChannelPaymentEvidence(
        ChannelEvidenceExistence existence,
        String rawStatus,
        String merchantNo,
        String outTradeNo,
        String channelTradeNo,
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

