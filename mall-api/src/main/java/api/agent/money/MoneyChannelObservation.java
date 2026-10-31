package api.agent.money;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * 渠道只读观察证据
 */
public class MoneyChannelObservation implements Serializable {
    private static final long serialVersionUID = 1L;

    private String observationNo;
    private String scopeKey;
    private String attemptNo;
    private String refundNo;
    /** SUCCESS, UNAVAILABLE, TIMEOUT */
    private String transportStatus;
    private Boolean exists;
    /** 渠道原始状态，如 SUCCESS, PROCESSING, FAILED, NOT_FOUND */
    private String rawStatus;
    private BigDecimal amount;
    private String currency;
    private String channelMerchantNo;
    private String channelTradeNo;
    private String channelRefundNo;
    private String channelSuccessAt;
    private String observedAt;
    private String environment; // MOCK, PRODUCTION
    private Boolean merchantMatched;
    private Boolean amountMatched;
    private Boolean currencyMatched;
    private String evidenceRef;
    private String errorMsg;

    public MoneyChannelObservation() {}

    public MoneyChannelObservation(String observationNo, String scopeKey, String attemptNo, String refundNo,
                                   String transportStatus, Boolean exists, String rawStatus, BigDecimal amount,
                                   String currency, String channelMerchantNo, String channelTradeNo,
                                   String channelRefundNo, String channelSuccessAt, String observedAt,
                                   String environment, Boolean merchantMatched, Boolean amountMatched,
                                   Boolean currencyMatched, String evidenceRef, String errorMsg) {
        this.observationNo = observationNo;
        this.scopeKey = scopeKey;
        this.attemptNo = attemptNo;
        this.refundNo = refundNo;
        this.transportStatus = transportStatus;
        this.exists = exists;
        this.rawStatus = rawStatus;
        this.amount = amount;
        this.currency = currency;
        this.channelMerchantNo = channelMerchantNo;
        this.channelTradeNo = channelTradeNo;
        this.channelRefundNo = channelRefundNo;
        this.channelSuccessAt = channelSuccessAt;
        this.observedAt = observedAt;
        this.environment = environment;
        this.merchantMatched = merchantMatched;
        this.amountMatched = amountMatched;
        this.currencyMatched = currencyMatched;
        this.evidenceRef = evidenceRef;
        this.errorMsg = errorMsg;
    }

    public static MoneyChannelObservation unavailable(String scopeKey, String attemptNo, String refundNo, String errorMsg) {
        MoneyChannelObservation o = new MoneyChannelObservation();
        o.setScopeKey(scopeKey);
        o.setAttemptNo(attemptNo);
        o.setRefundNo(refundNo);
        o.setTransportStatus("UNAVAILABLE");
        o.setExists(false);
        o.setErrorMsg(errorMsg);
        return o;
    }

    public String getObservationNo() { return observationNo; }
    public void setObservationNo(String observationNo) { this.observationNo = observationNo; }

    public String getScopeKey() { return scopeKey; }
    public void setScopeKey(String scopeKey) { this.scopeKey = scopeKey; }

    public String getAttemptNo() { return attemptNo; }
    public void setAttemptNo(String attemptNo) { this.attemptNo = attemptNo; }

    public String getRefundNo() { return refundNo; }
    public void setRefundNo(String refundNo) { this.refundNo = refundNo; }

    public String getTransportStatus() { return transportStatus; }
    public void setTransportStatus(String transportStatus) { this.transportStatus = transportStatus; }

    public Boolean getExists() { return exists; }
    public void setExists(Boolean exists) { this.exists = exists; }

    public String getRawStatus() { return rawStatus; }
    public void setRawStatus(String rawStatus) { this.rawStatus = rawStatus; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public String getChannelMerchantNo() { return channelMerchantNo; }
    public void setChannelMerchantNo(String channelMerchantNo) { this.channelMerchantNo = channelMerchantNo; }

    public String getChannelTradeNo() { return channelTradeNo; }
    public void setChannelTradeNo(String channelTradeNo) { this.channelTradeNo = channelTradeNo; }

    public String getChannelRefundNo() { return channelRefundNo; }
    public void setChannelRefundNo(String channelRefundNo) { this.channelRefundNo = channelRefundNo; }

    public String getChannelSuccessAt() { return channelSuccessAt; }
    public void setChannelSuccessAt(String channelSuccessAt) { this.channelSuccessAt = channelSuccessAt; }

    public String getObservedAt() { return observedAt; }
    public void setObservedAt(String observedAt) { this.observedAt = observedAt; }

    public String getEnvironment() { return environment; }
    public void setEnvironment(String environment) { this.environment = environment; }

    public Boolean getMerchantMatched() { return merchantMatched; }
    public void setMerchantMatched(Boolean merchantMatched) { this.merchantMatched = merchantMatched; }

    public Boolean getAmountMatched() { return amountMatched; }
    public void setAmountMatched(Boolean amountMatched) { this.amountMatched = amountMatched; }

    public Boolean getCurrencyMatched() { return currencyMatched; }
    public void setCurrencyMatched(Boolean currencyMatched) { this.currencyMatched = currencyMatched; }

    public String getEvidenceRef() { return evidenceRef; }
    public void setEvidenceRef(String evidenceRef) { this.evidenceRef = evidenceRef; }

    public String getErrorMsg() { return errorMsg; }
    public void setErrorMsg(String errorMsg) { this.errorMsg = errorMsg; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MoneyChannelObservation that)) return false;
        return Objects.equals(observationNo, that.observationNo) &&
                Objects.equals(scopeKey, that.scopeKey) &&
                Objects.equals(rawStatus, that.rawStatus);
    }

    @Override
    public int hashCode() {
        return Objects.hash(observationNo, scopeKey, rawStatus);
    }
}

