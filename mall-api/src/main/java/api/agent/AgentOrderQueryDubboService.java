package api.agent;

import java.io.Serializable;
import java.util.List;
import java.util.Objects;

/** Read-only, privacy-filtered customer service boundary. No payment or delivery secrets. */
public interface AgentOrderQueryDubboService {
    List<OrderFact> queryMyOrders(AgentActor actor, int limit);
    OrderFact queryOrder(AgentActor actor, String orderNo);
    List<TimelineFact> queryOrderTimeline(AgentActor actor, String orderNo);
    DisputeFact queryDispute(AgentActor actor, String disputeNo);
    List<EvidenceFact> queryDeliveryEvidenceSummary(AgentActor actor, String orderNo);
    OrderPartiesFact resolveOrderParties(String orderNo);

    class OrderFact implements Serializable {
        private static final long serialVersionUID = 1L;
        private String orderNo;
        private Long itemId;
        private String orderStatus;
        private String payStatus;
        private String deliveryStatus;
        private String escrowStatus;
        private String disputeStatus;
        private String createdTime;
        private String deliveredTime;
        private String settleAvailableTime;
        private String settledTime;

        public OrderFact() {}

        public OrderFact(String orderNo, Long itemId, String orderStatus, String payStatus,
                         String deliveryStatus, String escrowStatus, String disputeStatus,
                         String createdTime, String deliveredTime, String settleAvailableTime,
                         String settledTime) {
            this.orderNo = orderNo;
            this.itemId = itemId;
            this.orderStatus = orderStatus;
            this.payStatus = payStatus;
            this.deliveryStatus = deliveryStatus;
            this.escrowStatus = escrowStatus;
            this.disputeStatus = disputeStatus;
            this.createdTime = createdTime;
            this.deliveredTime = deliveredTime;
            this.settleAvailableTime = settleAvailableTime;
            this.settledTime = settledTime;
        }

        public String orderNo() { return orderNo; }
        public Long itemId() { return itemId; }
        public String orderStatus() { return orderStatus; }
        public String payStatus() { return payStatus; }
        public String deliveryStatus() { return deliveryStatus; }
        public String escrowStatus() { return escrowStatus; }
        public String disputeStatus() { return disputeStatus; }
        public String createdTime() { return createdTime; }
        public String deliveredTime() { return deliveredTime; }
        public String settleAvailableTime() { return settleAvailableTime; }
        public String settledTime() { return settledTime; }

        public String getOrderNo() { return orderNo; }
        public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
        public Long getItemId() { return itemId; }
        public void setItemId(Long itemId) { this.itemId = itemId; }
        public String getOrderStatus() { return orderStatus; }
        public void setOrderStatus(String orderStatus) { this.orderStatus = orderStatus; }
        public String getPayStatus() { return payStatus; }
        public void setPayStatus(String payStatus) { this.payStatus = payStatus; }
        public String getDeliveryStatus() { return deliveryStatus; }
        public void setDeliveryStatus(String deliveryStatus) { this.deliveryStatus = deliveryStatus; }
        public String getEscrowStatus() { return escrowStatus; }
        public void setEscrowStatus(String escrowStatus) { this.escrowStatus = escrowStatus; }
        public String getDisputeStatus() { return disputeStatus; }
        public void setDisputeStatus(String disputeStatus) { this.disputeStatus = disputeStatus; }
        public String getCreatedTime() { return createdTime; }
        public void setCreatedTime(String createdTime) { this.createdTime = createdTime; }
        public String getDeliveredTime() { return deliveredTime; }
        public void setDeliveredTime(String deliveredTime) { this.deliveredTime = deliveredTime; }
        public String getSettleAvailableTime() { return settleAvailableTime; }
        public void setSettleAvailableTime(String settleAvailableTime) { this.settleAvailableTime = settleAvailableTime; }
        public String getSettledTime() { return settledTime; }
        public void setSettledTime(String settledTime) { this.settledTime = settledTime; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof OrderFact that)) return false;
            return Objects.equals(orderNo, that.orderNo) && Objects.equals(itemId, that.itemId)
                && Objects.equals(orderStatus, that.orderStatus) && Objects.equals(payStatus, that.payStatus)
                && Objects.equals(deliveryStatus, that.deliveryStatus) && Objects.equals(escrowStatus, that.escrowStatus)
                && Objects.equals(disputeStatus, that.disputeStatus) && Objects.equals(createdTime, that.createdTime)
                && Objects.equals(deliveredTime, that.deliveredTime) && Objects.equals(settleAvailableTime, that.settleAvailableTime)
                && Objects.equals(settledTime, that.settledTime);
        }

        @Override
        public int hashCode() {
            return Objects.hash(orderNo, itemId, orderStatus, payStatus, deliveryStatus, escrowStatus,
                disputeStatus, createdTime, deliveredTime, settleAvailableTime, settledTime);
        }

        @Override
        public String toString() {
            return "OrderFact[orderNo=" + orderNo + ", itemId=" + itemId + ", orderStatus=" + orderStatus + "]";
        }
    }

    class TimelineFact implements Serializable {
        private static final long serialVersionUID = 1L;
        private String statusType;
        private String fromStatus;
        private String toStatus;
        private String createdTime;

        public TimelineFact() {}

        public TimelineFact(String statusType, String fromStatus, String toStatus, String createdTime) {
            this.statusType = statusType;
            this.fromStatus = fromStatus;
            this.toStatus = toStatus;
            this.createdTime = createdTime;
        }

        public String statusType() { return statusType; }
        public String fromStatus() { return fromStatus; }
        public String toStatus() { return toStatus; }
        public String createdTime() { return createdTime; }

        public String getStatusType() { return statusType; }
        public void setStatusType(String statusType) { this.statusType = statusType; }
        public String getFromStatus() { return fromStatus; }
        public void setFromStatus(String fromStatus) { this.fromStatus = fromStatus; }
        public String getToStatus() { return toStatus; }
        public void setToStatus(String toStatus) { this.toStatus = toStatus; }
        public String getCreatedTime() { return createdTime; }
        public void setCreatedTime(String createdTime) { this.createdTime = createdTime; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof TimelineFact that)) return false;
            return Objects.equals(statusType, that.statusType) && Objects.equals(fromStatus, that.fromStatus)
                && Objects.equals(toStatus, that.toStatus) && Objects.equals(createdTime, that.createdTime);
        }

        @Override
        public int hashCode() {
            return Objects.hash(statusType, fromStatus, toStatus, createdTime);
        }

        @Override
        public String toString() {
            return "TimelineFact[statusType=" + statusType + ", fromStatus=" + fromStatus + ", toStatus=" + toStatus + "]";
        }
    }

    class DisputeFact implements Serializable {
        private static final long serialVersionUID = 1L;
        private String disputeNo;
        private String orderNo;
        private String status;
        private String createdTime;
        private String updatedTime;

        public DisputeFact() {}

        public DisputeFact(String disputeNo, String orderNo, String status, String createdTime, String updatedTime) {
            this.disputeNo = disputeNo;
            this.orderNo = orderNo;
            this.status = status;
            this.createdTime = createdTime;
            this.updatedTime = updatedTime;
        }

        public String disputeNo() { return disputeNo; }
        public String orderNo() { return orderNo; }
        public String status() { return status; }
        public String createdTime() { return createdTime; }
        public String updatedTime() { return updatedTime; }

        public String getDisputeNo() { return disputeNo; }
        public void setDisputeNo(String disputeNo) { this.disputeNo = disputeNo; }
        public String getOrderNo() { return orderNo; }
        public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getCreatedTime() { return createdTime; }
        public void setCreatedTime(String createdTime) { this.createdTime = createdTime; }
        public String getUpdatedTime() { return updatedTime; }
        public void setUpdatedTime(String updatedTime) { this.updatedTime = updatedTime; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof DisputeFact that)) return false;
            return Objects.equals(disputeNo, that.disputeNo) && Objects.equals(orderNo, that.orderNo)
                && Objects.equals(status, that.status) && Objects.equals(createdTime, that.createdTime)
                && Objects.equals(updatedTime, that.updatedTime);
        }

        @Override
        public int hashCode() {
            return Objects.hash(disputeNo, orderNo, status, createdTime, updatedTime);
        }

        @Override
        public String toString() {
            return "DisputeFact[disputeNo=" + disputeNo + ", status=" + status + "]";
        }
    }

    class EvidenceFact implements Serializable {
        private static final long serialVersionUID = 1L;
        private String evidenceType;
        private String createdTime;

        public EvidenceFact() {}

        public EvidenceFact(String evidenceType, String createdTime) {
            this.evidenceType = evidenceType;
            this.createdTime = createdTime;
        }

        public String evidenceType() { return evidenceType; }
        public String createdTime() { return createdTime; }

        public String getEvidenceType() { return evidenceType; }
        public void setEvidenceType(String evidenceType) { this.evidenceType = evidenceType; }
        public String getCreatedTime() { return createdTime; }
        public void setCreatedTime(String createdTime) { this.createdTime = createdTime; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof EvidenceFact that)) return false;
            return Objects.equals(evidenceType, that.evidenceType) && Objects.equals(createdTime, that.createdTime);
        }

        @Override
        public int hashCode() {
            return Objects.hash(evidenceType, createdTime);
        }

        @Override
        public String toString() {
            return "EvidenceFact[evidenceType=" + evidenceType + "]";
        }
    }

    class OrderPartiesFact implements Serializable {
        private static final long serialVersionUID = 1L;
        private String orderNo;
        private Long buyerId;
        private Long sellerId;

        public OrderPartiesFact() {}

        public OrderPartiesFact(String orderNo, Long buyerId, Long sellerId) {
            this.orderNo = orderNo;
            this.buyerId = buyerId;
            this.sellerId = sellerId;
        }

        public String orderNo() { return orderNo; }
        public Long buyerId() { return buyerId; }
        public Long sellerId() { return sellerId; }

        public String getOrderNo() { return orderNo; }
        public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
        public Long getBuyerId() { return buyerId; }
        public void setBuyerId(Long buyerId) { this.buyerId = buyerId; }
        public Long getSellerId() { return sellerId; }
        public void setSellerId(Long sellerId) { this.sellerId = sellerId; }
    }
}
