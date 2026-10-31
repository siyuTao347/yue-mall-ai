package api.agent.money;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 平台资金账本只读快照
 */
public class MoneyFundSnapshot implements Serializable {
    private static final long serialVersionUID = 1L;

    private List<FundTransactionSnapshot> transactions = new ArrayList<>();
    private List<FundFlowSnapshot> flows = new ArrayList<>();
    private List<FundBusinessRefSnapshot> businessRefs = new ArrayList<>();
    private CoverageInfo coverage;
    private String observedAt;

    public MoneyFundSnapshot() {}

    public MoneyFundSnapshot(List<FundTransactionSnapshot> transactions, List<FundFlowSnapshot> flows,
                             List<FundBusinessRefSnapshot> businessRefs, CoverageInfo coverage, String observedAt) {
        if (transactions != null) this.transactions = new ArrayList<>(transactions);
        if (flows != null) this.flows = new ArrayList<>(flows);
        if (businessRefs != null) this.businessRefs = new ArrayList<>(businessRefs);
        this.coverage = coverage;
        this.observedAt = observedAt;
    }

    public List<FundTransactionSnapshot> getTransactions() { return transactions; }
    public void setTransactions(List<FundTransactionSnapshot> transactions) {
        this.transactions = transactions != null ? new ArrayList<>(transactions) : new ArrayList<>();
    }

    public List<FundFlowSnapshot> getFlows() { return flows; }
    public void setFlows(List<FundFlowSnapshot> flows) {
        this.flows = flows != null ? new ArrayList<>(flows) : new ArrayList<>();
    }

    public List<FundBusinessRefSnapshot> getBusinessRefs() { return businessRefs; }
    public void setBusinessRefs(List<FundBusinessRefSnapshot> businessRefs) {
        this.businessRefs = businessRefs != null ? new ArrayList<>(businessRefs) : new ArrayList<>();
    }

    public CoverageInfo getCoverage() { return coverage; }
    public void setCoverage(CoverageInfo coverage) { this.coverage = coverage; }

    public String getObservedAt() { return observedAt; }
    public void setObservedAt(String observedAt) { this.observedAt = observedAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MoneyFundSnapshot that)) return false;
        return Objects.equals(transactions, that.transactions) &&
                Objects.equals(flows, that.flows) &&
                Objects.equals(businessRefs, that.businessRefs);
    }

    @Override
    public int hashCode() {
        return Objects.hash(transactions, flows, businessRefs);
    }
}

