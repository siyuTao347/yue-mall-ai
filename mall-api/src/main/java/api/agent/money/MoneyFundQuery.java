package api.agent.money;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 资金账本查询条件 (受 Order scope proof 严格约束)
 */
public class MoneyFundQuery implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 由 Order 服务签发的受验证范围凭据 */
    private String scopeProofRef;
    private String orderNo;
    private List<String> verifiedAttemptRefs = new ArrayList<>();
    private List<String> verifiedRefundRefs = new ArrayList<>();
    private List<String> operationRefs = new ArrayList<>();

    public MoneyFundQuery() {}

    public MoneyFundQuery(String scopeProofRef, String orderNo, List<String> verifiedAttemptRefs,
                          List<String> verifiedRefundRefs, List<String> operationRefs) {
        this.scopeProofRef = scopeProofRef;
        this.orderNo = orderNo;
        if (verifiedAttemptRefs != null) this.verifiedAttemptRefs = new ArrayList<>(verifiedAttemptRefs);
        if (verifiedRefundRefs != null) this.verifiedRefundRefs = new ArrayList<>(verifiedRefundRefs);
        if (operationRefs != null) this.operationRefs = new ArrayList<>(operationRefs);
    }

    public String getScopeProofRef() { return scopeProofRef; }
    public void setScopeProofRef(String scopeProofRef) { this.scopeProofRef = scopeProofRef; }

    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }

    public List<String> getVerifiedAttemptRefs() { return verifiedAttemptRefs; }
    public void setVerifiedAttemptRefs(List<String> verifiedAttemptRefs) {
        this.verifiedAttemptRefs = verifiedAttemptRefs != null ? new ArrayList<>(verifiedAttemptRefs) : new ArrayList<>();
    }

    public List<String> getVerifiedRefundRefs() { return verifiedRefundRefs; }
    public void setVerifiedRefundRefs(List<String> verifiedRefundRefs) {
        this.verifiedRefundRefs = verifiedRefundRefs != null ? new ArrayList<>(verifiedRefundRefs) : new ArrayList<>();
    }

    public List<String> getOperationRefs() { return operationRefs; }
    public void setOperationRefs(List<String> operationRefs) {
        this.operationRefs = operationRefs != null ? new ArrayList<>(operationRefs) : new ArrayList<>();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MoneyFundQuery that)) return false;
        return Objects.equals(scopeProofRef, that.scopeProofRef) &&
                Objects.equals(orderNo, that.orderNo) &&
                Objects.equals(verifiedAttemptRefs, that.verifiedAttemptRefs) &&
                Objects.equals(verifiedRefundRefs, that.verifiedRefundRefs);
    }

    @Override
    public int hashCode() {
        return Objects.hash(scopeProofRef, orderNo, verifiedAttemptRefs, verifiedRefundRefs);
    }
}

