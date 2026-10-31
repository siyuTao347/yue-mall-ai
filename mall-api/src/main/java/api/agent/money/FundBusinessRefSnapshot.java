package api.agent.money;

import java.io.Serializable;
import java.util.Objects;

/**
 * 资金业务引用快照
 */
public class FundBusinessRefSnapshot implements Serializable {
    private static final long serialVersionUID = 1L;

    private String transactionNo;
    /** ORDER / ATTEMPT / REFUND / OPERATION */
    private String refType;
    private String refNo;
    /** TRANSACTION_BIND / IDEMPOTENT_KEY / VERIFIED_BACKFILL */
    private String linkOrigin;
    private String createdAt;

    public FundBusinessRefSnapshot() {}

    public FundBusinessRefSnapshot(String transactionNo, String refType, String refNo, String linkOrigin, String createdAt) {
        this.transactionNo = transactionNo;
        this.refType = refType;
        this.refNo = refNo;
        this.linkOrigin = linkOrigin;
        this.createdAt = createdAt;
    }

    public String getTransactionNo() { return transactionNo; }
    public void setTransactionNo(String transactionNo) { this.transactionNo = transactionNo; }

    public String getRefType() { return refType; }
    public void setRefType(String refType) { this.refType = refType; }

    public String getRefNo() { return refNo; }
    public void setRefNo(String refNo) { this.refNo = refNo; }

    public String getLinkOrigin() { return linkOrigin; }
    public void setLinkOrigin(String linkOrigin) { this.linkOrigin = linkOrigin; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof FundBusinessRefSnapshot that)) return false;
        return Objects.equals(transactionNo, that.transactionNo) &&
                Objects.equals(refType, that.refType) &&
                Objects.equals(refNo, that.refNo);
    }

    @Override
    public int hashCode() {
        return Objects.hash(transactionNo, refType, refNo);
    }
}

