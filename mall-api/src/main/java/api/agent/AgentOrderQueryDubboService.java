package api.agent;

import java.io.Serializable;
import java.util.List;

/** Read-only, privacy-filtered customer service boundary. No payment or delivery secrets. */
public interface AgentOrderQueryDubboService {
    List<OrderFact> queryMyOrders(AgentActor actor, int limit);
    OrderFact queryOrder(AgentActor actor, String orderNo);
    List<TimelineFact> queryOrderTimeline(AgentActor actor, String orderNo);
    DisputeFact queryDispute(AgentActor actor, String disputeNo);
    List<EvidenceFact> queryDeliveryEvidenceSummary(AgentActor actor, String orderNo);

    record OrderFact(String orderNo, Long itemId, String orderStatus, String payStatus,
                     String deliveryStatus, String escrowStatus, String disputeStatus,
                     String createdTime, String deliveredTime, String settleAvailableTime,
                     String settledTime) implements Serializable {}
    record TimelineFact(String statusType, String fromStatus, String toStatus,
                        String createdTime) implements Serializable {}
    record DisputeFact(String disputeNo, String orderNo, String status,
                       String createdTime, String updatedTime) implements Serializable {}
    record EvidenceFact(String evidenceType, String createdTime) implements Serializable {}
}
