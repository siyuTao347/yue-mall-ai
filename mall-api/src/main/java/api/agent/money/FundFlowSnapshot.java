package api.agent.money;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * 资金流水只读快照
 */
public class FundFlowSnapshot implements Serializable {
    private static final long serialVersionUID = 1L;

    private String transactionNo;
    private String ownerType;
    private Long ownerId;
    private String accountType;
    private String direction;
    private BigDecimal amount;
    private BigDecimal balanceAfter;
    private String memo;
    private String createdTime;

    public FundFlowSnapshot() {}

    public FundFlowSnapshot(String transactionNo, String ownerType, Long ownerId, String accountType,
                            String direction, BigDecimal amount, BigDecimal balanceAfter,
                            String memo, String createdTime) {
        this.transactionNo = transactionNo;
        this.ownerType = ownerType;
        this.ownerId = ownerId;
        this.accountType = accountType;
        this.direction = direction;
        this.amount = amount;
        this.balanceAfter = balanceAfter;
        this.memo = memo;
        this.createdTime = createdTime;
    }

    public String getTransactionNo() { return transactionNo; }
    public void setTransactionNo(String transactionNo) { this.transactionNo = transactionNo; }

    public String getOwnerType() { return ownerType; }
    public void setOwnerType(String ownerType) { this.ownerType = ownerType; }

    public Long getOwnerId() { return ownerId; }
    public void setOwnerId(Long ownerId) { this.ownerId = ownerId; }

    public String getAccountType() { return accountType; }
    public void setAccountType(String accountType) { this.accountType = accountType; }

    public String getDirection() { return direction; }
    public void setDirection(String direction) { this.direction = direction; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public BigDecimal getBalanceAfter() { return balanceAfter; }
    public void setBalanceAfter(BigDecimal balanceAfter) { this.balanceAfter = balanceAfter; }

    public String getMemo() { return memo; }
    public void setMemo(String memo) { this.memo = memo; }

    public String getCreatedTime() { return createdTime; }
    public void setCreatedTime(String createdTime) { this.createdTime = createdTime; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof FundFlowSnapshot that)) return false;
        return Objects.equals(transactionNo, that.transactionNo) &&
                Objects.equals(direction, that.direction) &&
                Objects.equals(accountType, that.accountType);
    }

    @Override
    public int hashCode() {
        return Objects.hash(transactionNo, direction, accountType);
    }
}

