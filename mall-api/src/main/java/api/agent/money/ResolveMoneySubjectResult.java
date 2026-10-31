package api.agent.money;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 目标解析结果（由 Order 服务权威解析并输出已授权范围证明）
 */
public class ResolveMoneySubjectResult implements Serializable {
    private static final long serialVersionUID = 1L;

    private String canonicalOrderNo;
    private String buyerRef;
    private String sellerRef;
    private String allowedView; // BUYER_VIEW, SELLER_VIEW, FINANCE_VIEW
    private List<String> verifiedAttemptRefs = new ArrayList<>();
    private List<String> verifiedRefundRefs = new ArrayList<>();
    private String scopeProofRef;
    private String scopeFingerprint;
    private String status; // SUCCESS, UNAUTHORIZED, NOT_FOUND, AMBIGUOUS
    private String errorMessage;

    public ResolveMoneySubjectResult() {}

    public ResolveMoneySubjectResult(String canonicalOrderNo, String buyerRef, String sellerRef,
                                     String allowedView, List<String> verifiedAttemptRefs,
                                     List<String> verifiedRefundRefs, String scopeProofRef,
                                     String scopeFingerprint, String status, String errorMessage) {
        this.canonicalOrderNo = canonicalOrderNo;
        this.buyerRef = buyerRef;
        this.sellerRef = sellerRef;
        this.allowedView = allowedView;
        if (verifiedAttemptRefs != null) {
            this.verifiedAttemptRefs = new ArrayList<>(verifiedAttemptRefs);
        }
        if (verifiedRefundRefs != null) {
            this.verifiedRefundRefs = new ArrayList<>(verifiedRefundRefs);
        }
        this.scopeProofRef = scopeProofRef;
        this.scopeFingerprint = scopeFingerprint;
        this.status = status;
        this.errorMessage = errorMessage;
    }

    public static ResolveMoneySubjectResult fail(String status, String errorMessage) {
        ResolveMoneySubjectResult r = new ResolveMoneySubjectResult();
        r.setStatus(status);
        r.setErrorMessage(errorMessage);
        return r;
    }

    public boolean isSuccess() {
        return "SUCCESS".equalsIgnoreCase(status);
    }

    public String getCanonicalOrderNo() { return canonicalOrderNo; }
    public void setCanonicalOrderNo(String canonicalOrderNo) { this.canonicalOrderNo = canonicalOrderNo; }

    public String getBuyerRef() { return buyerRef; }
    public void setBuyerRef(String buyerRef) { this.buyerRef = buyerRef; }

    public String getSellerRef() { return sellerRef; }
    public void setSellerRef(String sellerRef) { this.sellerRef = sellerRef; }

    public String getAllowedView() { return allowedView; }
    public void setAllowedView(String allowedView) { this.allowedView = allowedView; }

    public List<String> getVerifiedAttemptRefs() { return verifiedAttemptRefs; }
    public void setVerifiedAttemptRefs(List<String> verifiedAttemptRefs) {
        this.verifiedAttemptRefs = verifiedAttemptRefs != null ? new ArrayList<>(verifiedAttemptRefs) : new ArrayList<>();
    }

    public List<String> getVerifiedRefundRefs() { return verifiedRefundRefs; }
    public void setVerifiedRefundRefs(List<String> verifiedRefundRefs) {
        this.verifiedRefundRefs = verifiedRefundRefs != null ? new ArrayList<>(verifiedRefundRefs) : new ArrayList<>();
    }

    public String getScopeProofRef() { return scopeProofRef; }
    public void setScopeProofRef(String scopeProofRef) { this.scopeProofRef = scopeProofRef; }

    public String getScopeFingerprint() { return scopeFingerprint; }
    public void setScopeFingerprint(String scopeFingerprint) { this.scopeFingerprint = scopeFingerprint; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ResolveMoneySubjectResult that)) return false;
        return Objects.equals(canonicalOrderNo, that.canonicalOrderNo) &&
                Objects.equals(buyerRef, that.buyerRef) &&
                Objects.equals(sellerRef, that.sellerRef) &&
                Objects.equals(allowedView, that.allowedView) &&
                Objects.equals(verifiedAttemptRefs, that.verifiedAttemptRefs) &&
                Objects.equals(verifiedRefundRefs, that.verifiedRefundRefs) &&
                Objects.equals(scopeProofRef, that.scopeProofRef) &&
                Objects.equals(scopeFingerprint, that.scopeFingerprint) &&
                Objects.equals(status, that.status);
    }

    @Override
    public int hashCode() {
        return Objects.hash(canonicalOrderNo, buyerRef, sellerRef, allowedView, verifiedAttemptRefs, verifiedRefundRefs, scopeProofRef, scopeFingerprint, status);
    }
}

