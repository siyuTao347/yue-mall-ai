package api.agent.money;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * 对账差异摘要快照
 */
public class ReconDiffSummarySnapshot implements Serializable {
    private static final long serialVersionUID = 1L;

    private String diffNo;
    private String diffType;
    private String bizKey;
    private String orderNo;
    private String attemptNo;
    private String refundNo;
    private BigDecimal channelAmount;
    private BigDecimal localAmount;
    private String status;

    public ReconDiffSummarySnapshot() {}

    public ReconDiffSummarySnapshot(String diffNo, String diffType, String bizKey, String orderNo,
                                    String attemptNo, String refundNo, BigDecimal channelAmount,
                                    BigDecimal localAmount, String status) {
        this.diffNo = diffNo;
        this.diffType = diffType;
        this.bizKey = bizKey;
        this.orderNo = orderNo;
        this.attemptNo = attemptNo;
        this.refundNo = refundNo;
        this.channelAmount = channelAmount;
        this.localAmount = localAmount;
        this.status = status;
    }

    public String getDiffNo() { return diffNo; }
    public void setDiffNo(String diffNo) { this.diffNo = diffNo; }

    public String getDiffType() { return diffType; }
    public void setDiffType(String diffType) { this.diffType = diffType; }

    public String getBizKey() { return bizKey; }
    public void setBizKey(String bizKey) { this.bizKey = bizKey; }

    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }

    public String getAttemptNo() { return attemptNo; }
    public void setAttemptNo(String attemptNo) { this.attemptNo = attemptNo; }

    public String getRefundNo() { return refundNo; }
    public void setRefundNo(String refundNo) { this.refundNo = refundNo; }

    public BigDecimal getChannelAmount() { return channelAmount; }
    public void setChannelAmount(BigDecimal channelAmount) { this.channelAmount = channelAmount; }

    public BigDecimal getLocalAmount() { return localAmount; }
    public void setLocalAmount(BigDecimal localAmount) { this.localAmount = localAmount; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ReconDiffSummarySnapshot that)) return false;
        return Objects.equals(diffNo, that.diffNo) && Objects.equals(status, that.status);
    }

    @Override
    public int hashCode() {
        return Objects.hash(diffNo, status);
    }
}

