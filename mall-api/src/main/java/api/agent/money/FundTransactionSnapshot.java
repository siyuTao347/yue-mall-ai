package api.agent.money;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * 资金事务只读快照
 */
public class FundTransactionSnapshot implements Serializable {
    private static final long serialVersionUID = 1L;

    private String transactionNo;
    private String businessType;
    private String orderNo;
    private String withdrawNo;
    private Long merchantId;
    private Long userId;
    private BigDecimal amount;
    private String status;
    private String idempotencyKey;
    private String remark;
    private String createdTime;

    public FundTransactionSnapshot() {}

    public FundTransactionSnapshot(String transactionNo, String businessType, String orderNo,
                                   String withdrawNo, Long merchantId, Long userId,
                                   BigDecimal amount, String status, String idempotencyKey,
                                   String remark, String createdTime) {
        this.transactionNo = transactionNo;
        this.businessType = businessType;
        this.orderNo = orderNo;
        this.withdrawNo = withdrawNo;
        this.merchantId = merchantId;
        this.userId = userId;
        this.amount = amount;
        this.status = status;
        this.idempotencyKey = idempotencyKey;
        this.remark = remark;
        this.createdTime = createdTime;
    }

    public String getTransactionNo() { return transactionNo; }
    public void setTransactionNo(String transactionNo) { this.transactionNo = transactionNo; }

    public String getBusinessType() { return businessType; }
    public void setBusinessType(String businessType) { this.businessType = businessType; }

    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }

    public String getWithdrawNo() { return withdrawNo; }
    public void setWithdrawNo(String withdrawNo) { this.withdrawNo = withdrawNo; }

    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }

    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }

    public String getCreatedTime() { return createdTime; }
    public void setCreatedTime(String createdTime) { this.createdTime = createdTime; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof FundTransactionSnapshot that)) return false;
        return Objects.equals(transactionNo, that.transactionNo) && Objects.equals(status, that.status);
    }

    @Override
    public int hashCode() {
        return Objects.hash(transactionNo, status);
    }
}

