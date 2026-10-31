package api.agent.money;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * 支付尝试只读快照
 */
public class PaymentAttemptSnapshot implements Serializable {
    private static final long serialVersionUID = 1L;

    private String attemptNo;
    private String paymentNo;
    private String orderNo;
    private Integer attemptSeq;
    private String paymentMethod;
    private String profileId;
    private Integer profileVersion;
    private String channelMerchantNo;
    private String channelTradeNo;
    private String outTradeNo;
    private BigDecimal amount;
    private String currency;
    private String status;
    private String allocationStatus;
    private String channelSuccessAt;
    private String channelBusinessDate;
    private String localConfirmedAt;
    private Long version;

    public PaymentAttemptSnapshot() {}

    public PaymentAttemptSnapshot(String attemptNo, String paymentNo, String orderNo, Integer attemptSeq,
                                  String paymentMethod, String profileId, Integer profileVersion,
                                  String channelMerchantNo, String channelTradeNo, String outTradeNo,
                                  BigDecimal amount, String currency, String status, String allocationStatus,
                                  String channelSuccessAt, String channelBusinessDate, String localConfirmedAt,
                                  Long version) {
        this.attemptNo = attemptNo;
        this.paymentNo = paymentNo;
        this.orderNo = orderNo;
        this.attemptSeq = attemptSeq;
        this.paymentMethod = paymentMethod;
        this.profileId = profileId;
        this.profileVersion = profileVersion;
        this.channelMerchantNo = channelMerchantNo;
        this.channelTradeNo = channelTradeNo;
        this.outTradeNo = outTradeNo;
        this.amount = amount;
        this.currency = currency;
        this.status = status;
        this.allocationStatus = allocationStatus;
        this.channelSuccessAt = channelSuccessAt;
        this.channelBusinessDate = channelBusinessDate;
        this.localConfirmedAt = localConfirmedAt;
        this.version = version;
    }

    public String getAttemptNo() { return attemptNo; }
    public void setAttemptNo(String attemptNo) { this.attemptNo = attemptNo; }

    public String getPaymentNo() { return paymentNo; }
    public void setPaymentNo(String paymentNo) { this.paymentNo = paymentNo; }

    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }

    public Integer getAttemptSeq() { return attemptSeq; }
    public void setAttemptSeq(Integer attemptSeq) { this.attemptSeq = attemptSeq; }

    public String getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(String paymentMethod) { this.paymentMethod = paymentMethod; }

    public String getProfileId() { return profileId; }
    public void setProfileId(String profileId) { this.profileId = profileId; }

    public Integer getProfileVersion() { return profileVersion; }
    public void setProfileVersion(Integer profileVersion) { this.profileVersion = profileVersion; }

    public String getChannelMerchantNo() { return channelMerchantNo; }
    public void setChannelMerchantNo(String channelMerchantNo) { this.channelMerchantNo = channelMerchantNo; }

    public String getChannelTradeNo() { return channelTradeNo; }
    public void setChannelTradeNo(String channelTradeNo) { this.channelTradeNo = channelTradeNo; }

    public String getOutTradeNo() { return outTradeNo; }
    public void setOutTradeNo(String outTradeNo) { this.outTradeNo = outTradeNo; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getAllocationStatus() { return allocationStatus; }
    public void setAllocationStatus(String allocationStatus) { this.allocationStatus = allocationStatus; }

    public String getChannelSuccessAt() { return channelSuccessAt; }
    public void setChannelSuccessAt(String channelSuccessAt) { this.channelSuccessAt = channelSuccessAt; }

    public String getChannelBusinessDate() { return channelBusinessDate; }
    public void setChannelBusinessDate(String channelBusinessDate) { this.channelBusinessDate = channelBusinessDate; }

    public String getLocalConfirmedAt() { return localConfirmedAt; }
    public void setLocalConfirmedAt(String localConfirmedAt) { this.localConfirmedAt = localConfirmedAt; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PaymentAttemptSnapshot that)) return false;
        return Objects.equals(attemptNo, that.attemptNo) && Objects.equals(orderNo, that.orderNo) &&
                Objects.equals(status, that.status) && Objects.equals(amount, that.amount);
    }

    @Override
    public int hashCode() {
        return Objects.hash(attemptNo, orderNo, status, amount);
    }
}

