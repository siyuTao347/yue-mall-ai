package api.agent.money;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * 平台金融事件只读快照
 */
public class PaymentFinancialEventSnapshot implements Serializable {
    private static final long serialVersionUID = 1L;

    private String eventNo;
    private String profileId;
    private Integer profileVersion;
    private String channelMerchantNo;
    private String accountId;
    private String eventType;
    private String eventStage;
    private String bizType;
    private String bizNo;
    private String orderNo;
    private BigDecimal amount;
    private BigDecimal feeAmount;
    private String currency;
    private String channelBusinessDate;
    private String occurredAt;
    private String recordedAt;

    public PaymentFinancialEventSnapshot() {}

    public PaymentFinancialEventSnapshot(String eventNo, String profileId, Integer profileVersion,
                                         String channelMerchantNo, String accountId, String eventType,
                                         String eventStage, String bizType, String bizNo, String orderNo,
                                         BigDecimal amount, BigDecimal feeAmount, String currency,
                                         String channelBusinessDate, String occurredAt, String recordedAt) {
        this.eventNo = eventNo;
        this.profileId = profileId;
        this.profileVersion = profileVersion;
        this.channelMerchantNo = channelMerchantNo;
        this.accountId = accountId;
        this.eventType = eventType;
        this.eventStage = eventStage;
        this.bizType = bizType;
        this.bizNo = bizNo;
        this.orderNo = orderNo;
        this.amount = amount;
        this.feeAmount = feeAmount;
        this.currency = currency;
        this.channelBusinessDate = channelBusinessDate;
        this.occurredAt = occurredAt;
        this.recordedAt = recordedAt;
    }

    public String getEventNo() { return eventNo; }
    public void setEventNo(String eventNo) { this.eventNo = eventNo; }

    public String getProfileId() { return profileId; }
    public void setProfileId(String profileId) { this.profileId = profileId; }

    public Integer getProfileVersion() { return profileVersion; }
    public void setProfileVersion(Integer profileVersion) { this.profileVersion = profileVersion; }

    public String getChannelMerchantNo() { return channelMerchantNo; }
    public void setChannelMerchantNo(String channelMerchantNo) { this.channelMerchantNo = channelMerchantNo; }

    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }

    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }

    public String getEventStage() { return eventStage; }
    public void setEventStage(String eventStage) { this.eventStage = eventStage; }

    public String getBizType() { return bizType; }
    public void setBizType(String bizType) { this.bizType = bizType; }

    public String getBizNo() { return bizNo; }
    public void setBizNo(String bizNo) { this.bizNo = bizNo; }

    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public BigDecimal getFeeAmount() { return feeAmount; }
    public void setFeeAmount(BigDecimal feeAmount) { this.feeAmount = feeAmount; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public String getChannelBusinessDate() { return channelBusinessDate; }
    public void setChannelBusinessDate(String channelBusinessDate) { this.channelBusinessDate = channelBusinessDate; }

    public String getOccurredAt() { return occurredAt; }
    public void setOccurredAt(String occurredAt) { this.occurredAt = occurredAt; }

    public String getRecordedAt() { return recordedAt; }
    public void setRecordedAt(String recordedAt) { this.recordedAt = recordedAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PaymentFinancialEventSnapshot that)) return false;
        return Objects.equals(eventNo, that.eventNo) && Objects.equals(bizNo, that.bizNo) &&
                Objects.equals(eventStage, that.eventStage);
    }

    @Override
    public int hashCode() {
        return Objects.hash(eventNo, bizNo, eventStage);
    }
}

