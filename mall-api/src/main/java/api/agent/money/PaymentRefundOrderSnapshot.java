package api.agent.money;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * 退款订单只读快照
 */
public class PaymentRefundOrderSnapshot implements Serializable {
    private static final long serialVersionUID = 1L;

    private String refundNo;
    private String originalAttemptNo;
    private String orderNo;
    private BigDecimal amount;
    private String currency;
    private String reason;
    private String status;
    private String channelRefundNo;
    private String fundTaskNo;
    private String acceptedAt;
    private String successAt;
    private Long version;

    public PaymentRefundOrderSnapshot() {}

    public PaymentRefundOrderSnapshot(String refundNo, String originalAttemptNo, String orderNo,
                                      BigDecimal amount, String currency, String reason, String status,
                                      String channelRefundNo, String fundTaskNo, String acceptedAt,
                                      String successAt, Long version) {
        this.refundNo = refundNo;
        this.originalAttemptNo = originalAttemptNo;
        this.orderNo = orderNo;
        this.amount = amount;
        this.currency = currency;
        this.reason = reason;
        this.status = status;
        this.channelRefundNo = channelRefundNo;
        this.fundTaskNo = fundTaskNo;
        this.acceptedAt = acceptedAt;
        this.successAt = successAt;
        this.version = version;
    }

    public String getRefundNo() { return refundNo; }
    public void setRefundNo(String refundNo) { this.refundNo = refundNo; }

    public String getOriginalAttemptNo() { return originalAttemptNo; }
    public void setOriginalAttemptNo(String originalAttemptNo) { this.originalAttemptNo = originalAttemptNo; }

    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getChannelRefundNo() { return channelRefundNo; }
    public void setChannelRefundNo(String channelRefundNo) { this.channelRefundNo = channelRefundNo; }

    public String getFundTaskNo() { return fundTaskNo; }
    public void setFundTaskNo(String fundTaskNo) { this.fundTaskNo = fundTaskNo; }

    public String getAcceptedAt() { return acceptedAt; }
    public void setAcceptedAt(String acceptedAt) { this.acceptedAt = acceptedAt; }

    public String getSuccessAt() { return successAt; }
    public void setSuccessAt(String successAt) { this.successAt = successAt; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PaymentRefundOrderSnapshot that)) return false;
        return Objects.equals(refundNo, that.refundNo) && Objects.equals(orderNo, that.orderNo) &&
                Objects.equals(status, that.status) && Objects.equals(amount, that.amount);
    }

    @Override
    public int hashCode() {
        return Objects.hash(refundNo, orderNo, status, amount);
    }
}

