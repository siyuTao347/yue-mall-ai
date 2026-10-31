package api.agent.money;

import java.io.Serializable;
import java.util.Objects;

/**
 * 对账证据查询条件
 */
public class MoneyReconQuery implements Serializable {
    private static final long serialVersionUID = 1L;

    private String diffNo;
    private String orderNo;

    public MoneyReconQuery() {}

    public MoneyReconQuery(String diffNo, String orderNo) {
        this.diffNo = diffNo;
        this.orderNo = orderNo;
    }

    public static MoneyReconQuery forDiffNo(String diffNo) {
        return new MoneyReconQuery(diffNo, null);
    }

    public String getDiffNo() { return diffNo; }
    public void setDiffNo(String diffNo) { this.diffNo = diffNo; }

    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MoneyReconQuery that)) return false;
        return Objects.equals(diffNo, that.diffNo) && Objects.equals(orderNo, that.orderNo);
    }

    @Override
    public int hashCode() {
        return Objects.hash(diffNo, orderNo);
    }
}

