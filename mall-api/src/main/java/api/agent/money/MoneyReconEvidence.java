package api.agent.money;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * 对账回溯证据
 */
public class MoneyReconEvidence implements Serializable {
    private static final long serialVersionUID = 1L;

    private String diffNo;
    private String diffType;
    private String bizKey;
    private String orderNo;
    private String attemptNo;
    private String refundNo;
    /** 重新判定的原始条目类型：PAYMENT / REFUND / UNKNOWN */
    private String resolvedItemType;
    private String runNo;
    private String billDate;
    private Boolean windowClosed;
    private String channelItemRef;
    private BigDecimal channelAmount;
    private String channelStatus;
    private String localItemRef;
    private BigDecimal localAmount;
    private String localStatus;
    private CoverageInfo coverage;
    private String verifiedAt;
    private String notes;

    public MoneyReconEvidence() {}

    public MoneyReconEvidence(String diffNo, String diffType, String bizKey, String orderNo,
                              String attemptNo, String refundNo, String resolvedItemType,
                              String runNo, String billDate, Boolean windowClosed,
                              String channelItemRef, BigDecimal channelAmount, String channelStatus,
                              String localItemRef, BigDecimal localAmount, String localStatus,
                              CoverageInfo coverage, String verifiedAt, String notes) {
        this.diffNo = diffNo;
        this.diffType = diffType;
        this.bizKey = bizKey;
        this.orderNo = orderNo;
        this.attemptNo = attemptNo;
        this.refundNo = refundNo;
        this.resolvedItemType = resolvedItemType;
        this.runNo = runNo;
        this.billDate = billDate;
        this.windowClosed = windowClosed;
        this.channelItemRef = channelItemRef;
        this.channelAmount = channelAmount;
        this.channelStatus = channelStatus;
        this.localItemRef = localItemRef;
        this.localAmount = localAmount;
        this.localStatus = localStatus;
        this.coverage = coverage;
        this.verifiedAt = verifiedAt;
        this.notes = notes;
    }

    public static MoneyReconEvidence notFound(String diffNo) {
        MoneyReconEvidence e = new MoneyReconEvidence();
        e.setDiffNo(diffNo);
        e.setNotes("RECON_DIFF_NOT_FOUND");
        return e;
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

    public String getResolvedItemType() { return resolvedItemType; }
    public void setResolvedItemType(String resolvedItemType) { this.resolvedItemType = resolvedItemType; }

    public String getRunNo() { return runNo; }
    public void setRunNo(String runNo) { this.runNo = runNo; }

    public String getBillDate() { return billDate; }
    public void setBillDate(String billDate) { this.billDate = billDate; }

    public Boolean getWindowClosed() { return windowClosed; }
    public void setWindowClosed(Boolean windowClosed) { this.windowClosed = windowClosed; }

    public String getChannelItemRef() { return channelItemRef; }
    public void setChannelItemRef(String channelItemRef) { this.channelItemRef = channelItemRef; }

    public BigDecimal getChannelAmount() { return channelAmount; }
    public void setChannelAmount(BigDecimal channelAmount) { this.channelAmount = channelAmount; }

    public String getChannelStatus() { return channelStatus; }
    public void setChannelStatus(String channelStatus) { this.channelStatus = channelStatus; }

    public String getLocalItemRef() { return localItemRef; }
    public void setLocalItemRef(String localItemRef) { this.localItemRef = localItemRef; }

    public BigDecimal getLocalAmount() { return localAmount; }
    public void setLocalAmount(BigDecimal localAmount) { this.localAmount = localAmount; }

    public String getLocalStatus() { return localStatus; }
    public void setLocalStatus(String localStatus) { this.localStatus = localStatus; }

    public CoverageInfo getCoverage() { return coverage; }
    public void setCoverage(CoverageInfo coverage) { this.coverage = coverage; }

    public String getVerifiedAt() { return verifiedAt; }
    public void setVerifiedAt(String verifiedAt) { this.verifiedAt = verifiedAt; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MoneyReconEvidence that)) return false;
        return Objects.equals(diffNo, that.diffNo) && Objects.equals(runNo, that.runNo);
    }

    @Override
    public int hashCode() {
        return Objects.hash(diffNo, runNo);
    }
}

