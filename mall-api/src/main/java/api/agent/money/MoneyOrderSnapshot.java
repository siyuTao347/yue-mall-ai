package api.agent.money;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 订单与支付完整只读快照
 */
public class MoneyOrderSnapshot implements Serializable {
    private static final long serialVersionUID = 1L;

    private String scopeKey;
    private String canonicalOrderNo;
    private String paymentNo;
    private String paymentStatus;
    private List<PaymentAttemptSnapshot> attempts = new ArrayList<>();
    private List<PaymentRefundOrderSnapshot> refunds = new ArrayList<>();
    private List<PaymentFinancialEventSnapshot> financialEvents = new ArrayList<>();
    private TradeOrchestrationSnapshot orchestrationState;
    private List<ReconDiffSummarySnapshot> diffSummaries = new ArrayList<>();
    private CoverageInfo coverage;
    private String snapshotFingerprint;
    private String observedAt;

    public MoneyOrderSnapshot() {}

    public MoneyOrderSnapshot(String scopeKey, String canonicalOrderNo, String paymentNo, String paymentStatus,
                              List<PaymentAttemptSnapshot> attempts, List<PaymentRefundOrderSnapshot> refunds,
                              List<PaymentFinancialEventSnapshot> financialEvents,
                              TradeOrchestrationSnapshot orchestrationState,
                              List<ReconDiffSummarySnapshot> diffSummaries,
                              CoverageInfo coverage, String snapshotFingerprint, String observedAt) {
        this.scopeKey = scopeKey;
        this.canonicalOrderNo = canonicalOrderNo;
        this.paymentNo = paymentNo;
        this.paymentStatus = paymentStatus;
        if (attempts != null) this.attempts = new ArrayList<>(attempts);
        if (refunds != null) this.refunds = new ArrayList<>(refunds);
        if (financialEvents != null) this.financialEvents = new ArrayList<>(financialEvents);
        this.orchestrationState = orchestrationState;
        if (diffSummaries != null) this.diffSummaries = new ArrayList<>(diffSummaries);
        this.coverage = coverage;
        this.snapshotFingerprint = snapshotFingerprint;
        this.observedAt = observedAt;
    }

    public String getScopeKey() { return scopeKey; }
    public void setScopeKey(String scopeKey) { this.scopeKey = scopeKey; }

    public String getCanonicalOrderNo() { return canonicalOrderNo; }
    public void setCanonicalOrderNo(String canonicalOrderNo) { this.canonicalOrderNo = canonicalOrderNo; }

    public String getPaymentNo() { return paymentNo; }
    public void setPaymentNo(String paymentNo) { this.paymentNo = paymentNo; }

    public String getPaymentStatus() { return paymentStatus; }
    public void setPaymentStatus(String paymentStatus) { this.paymentStatus = paymentStatus; }

    public List<PaymentAttemptSnapshot> getAttempts() { return attempts; }
    public void setAttempts(List<PaymentAttemptSnapshot> attempts) {
        this.attempts = attempts != null ? new ArrayList<>(attempts) : new ArrayList<>();
    }

    public List<PaymentRefundOrderSnapshot> getRefunds() { return refunds; }
    public void setRefunds(List<PaymentRefundOrderSnapshot> refunds) {
        this.refunds = refunds != null ? new ArrayList<>(refunds) : new ArrayList<>();
    }

    public List<PaymentFinancialEventSnapshot> getFinancialEvents() { return financialEvents; }
    public void setFinancialEvents(List<PaymentFinancialEventSnapshot> financialEvents) {
        this.financialEvents = financialEvents != null ? new ArrayList<>(financialEvents) : new ArrayList<>();
    }

    public TradeOrchestrationSnapshot getOrchestrationState() { return orchestrationState; }
    public void setOrchestrationState(TradeOrchestrationSnapshot orchestrationState) { this.orchestrationState = orchestrationState; }

    public List<ReconDiffSummarySnapshot> getDiffSummaries() { return diffSummaries; }
    public void setDiffSummaries(List<ReconDiffSummarySnapshot> diffSummaries) {
        this.diffSummaries = diffSummaries != null ? new ArrayList<>(diffSummaries) : new ArrayList<>();
    }

    public CoverageInfo getCoverage() { return coverage; }
    public void setCoverage(CoverageInfo coverage) { this.coverage = coverage; }

    public String getSnapshotFingerprint() { return snapshotFingerprint; }
    public void setSnapshotFingerprint(String snapshotFingerprint) { this.snapshotFingerprint = snapshotFingerprint; }

    public String getObservedAt() { return observedAt; }
    public void setObservedAt(String observedAt) { this.observedAt = observedAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MoneyOrderSnapshot that)) return false;
        return Objects.equals(scopeKey, that.scopeKey) &&
                Objects.equals(canonicalOrderNo, that.canonicalOrderNo) &&
                Objects.equals(snapshotFingerprint, that.snapshotFingerprint);
    }

    @Override
    public int hashCode() {
        return Objects.hash(scopeKey, canonicalOrderNo, snapshotFingerprint);
    }
}

