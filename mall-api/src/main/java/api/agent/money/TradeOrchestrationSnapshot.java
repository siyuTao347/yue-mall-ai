package api.agent.money;

import java.io.Serializable;
import java.util.Objects;

/**
 * 交易编排只读快照
 */
public class TradeOrchestrationSnapshot implements Serializable {
    private static final long serialVersionUID = 1L;

    private String taskNo;
    private String orderNo;
    private String bizType;
    private String status;
    private String currentStep;
    private String lastError;

    public TradeOrchestrationSnapshot() {}

    public TradeOrchestrationSnapshot(String taskNo, String orderNo, String bizType, String status, String currentStep, String lastError) {
        this.taskNo = taskNo;
        this.orderNo = orderNo;
        this.bizType = bizType;
        this.status = status;
        this.currentStep = currentStep;
        this.lastError = lastError;
    }

    public String getTaskNo() { return taskNo; }
    public void setTaskNo(String taskNo) { this.taskNo = taskNo; }

    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }

    public String getBizType() { return bizType; }
    public void setBizType(String bizType) { this.bizType = bizType; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getCurrentStep() { return currentStep; }
    public void setCurrentStep(String currentStep) { this.currentStep = currentStep; }

    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TradeOrchestrationSnapshot that)) return false;
        return Objects.equals(taskNo, that.taskNo) && Objects.equals(orderNo, that.orderNo) &&
                Objects.equals(status, that.status);
    }

    @Override
    public int hashCode() {
        return Objects.hash(taskNo, orderNo, status);
    }
}

