package api.agent.money;

import java.io.Serializable;
import java.util.Objects;

/**
 * 调查目标选择器（支持按 orderNo、attemptNo、refundNo、diffNo 定位）
 */
public class MoneySubjectSelector implements Serializable {
    private static final long serialVersionUID = 1L;

    private String scopeKey;
    private String orderNo;
    private String attemptNo;
    private String refundNo;
    private String diffNo;

    public MoneySubjectSelector() {}

    public MoneySubjectSelector(String scopeKey, String orderNo, String attemptNo, String refundNo, String diffNo) {
        this.scopeKey = scopeKey;
        this.orderNo = orderNo;
        this.attemptNo = attemptNo;
        this.refundNo = refundNo;
        this.diffNo = diffNo;
    }

    public static MoneySubjectSelector forOrder(String orderNo) {
        MoneySubjectSelector s = new MoneySubjectSelector();
        s.setOrderNo(orderNo);
        s.setScopeKey("ORDER:" + orderNo);
        return s;
    }

    public static MoneySubjectSelector forAttempt(String attemptNo) {
        MoneySubjectSelector s = new MoneySubjectSelector();
        s.setAttemptNo(attemptNo);
        return s;
    }

    public static MoneySubjectSelector forRefund(String refundNo) {
        MoneySubjectSelector s = new MoneySubjectSelector();
        s.setRefundNo(refundNo);
        return s;
    }

    public static MoneySubjectSelector forDiff(String diffNo) {
        MoneySubjectSelector s = new MoneySubjectSelector();
        s.setDiffNo(diffNo);
        return s;
    }

    public String getScopeKey() { return scopeKey; }
    public void setScopeKey(String scopeKey) { this.scopeKey = scopeKey; }

    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }

    public String getAttemptNo() { return attemptNo; }
    public void setAttemptNo(String attemptNo) { this.attemptNo = attemptNo; }

    public String getRefundNo() { return refundNo; }
    public void setRefundNo(String refundNo) { this.refundNo = refundNo; }

    public String getDiffNo() { return diffNo; }
    public void setDiffNo(String diffNo) { this.diffNo = diffNo; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MoneySubjectSelector that)) return false;
        return Objects.equals(scopeKey, that.scopeKey) && Objects.equals(orderNo, that.orderNo) &&
                Objects.equals(attemptNo, that.attemptNo) && Objects.equals(refundNo, that.refundNo) &&
                Objects.equals(diffNo, that.diffNo);
    }

    @Override
    public int hashCode() {
        return Objects.hash(scopeKey, orderNo, attemptNo, refundNo, diffNo);
    }
}

