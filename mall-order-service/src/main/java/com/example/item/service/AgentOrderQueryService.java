package com.example.item.service;

import api.agent.AgentActor;
import api.agent.AgentOrderQueryDubboService;
import api.exception.ForbiddenException;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.item.entity.*;
import com.example.item.mapper.*;
import org.apache.dubbo.config.annotation.DubboService;
import java.util.List;
import java.util.Objects;

@DubboService(timeout = 3000, retries = 0)
public class AgentOrderQueryService implements AgentOrderQueryDubboService {
    private final TradeOrderMapper orders;
    private final DisputeMapper disputes;
    private final TradeOrderStatusLogMapper timeline;
    private final DeliveryEvidenceMapper evidence;
    public AgentOrderQueryService(TradeOrderMapper orders, DisputeMapper disputes,
                                  TradeOrderStatusLogMapper timeline, DeliveryEvidenceMapper evidence) {
        this.orders=orders; this.disputes=disputes; this.timeline=timeline; this.evidence=evidence;
    }
    static boolean owns(AgentActor actor, Long buyer, Long seller) {
        return actor != null && (Objects.equals(actor.userId(), buyer) || Objects.equals(actor.userId(), seller));
    }
    private TradeOrder require(AgentActor actor, String no) {
        if (actor == null || no == null || no.length()>64) throw denied();
        TradeOrder order=orders.selectOne(new LambdaQueryWrapper<TradeOrder>().eq(TradeOrder::getOrderNo,no));
        if (order==null || !owns(actor,order.getBuyerId(),order.getSellerId())) throw denied();
        return order;
    }
    private static ForbiddenException denied() { return new ForbiddenException("AGENT_PERMISSION_DENIED", "无法查询此业务记录"); }
    private static String time(Object value) { return value==null?null:value.toString(); }
    private static OrderFact fact(TradeOrder o) {
        return new OrderFact(o.getOrderNo(),o.getItemId(),o.getOrderStatus(),o.getPayStatus(),o.getDeliveryStatus(),
            o.getEscrowStatus(),o.getDisputeStatus(),time(o.getCreatedTime()),time(o.getDeliveredTime()),
            time(o.getSettleAvailableTime()),time(o.getSettledTime()));
    }
    public List<OrderFact> queryMyOrders(AgentActor actor,int limit) {
        if(actor==null)throw denied();
        return orders.selectList(new LambdaQueryWrapper<TradeOrder>()
            .and(q->q.eq(TradeOrder::getBuyerId,actor.userId()).or().eq(TradeOrder::getSellerId,actor.userId()))
            .orderByDesc(TradeOrder::getId).last("LIMIT "+Math.clamp(limit,1,5))).stream().map(AgentOrderQueryService::fact).toList();
    }
    public OrderFact queryOrder(AgentActor actor,String no) {return fact(require(actor,no));}
    public List<TimelineFact> queryOrderTimeline(AgentActor actor,String no) {
        require(actor,no);
        return timeline.selectList(new LambdaQueryWrapper<TradeOrderStatusLog>().eq(TradeOrderStatusLog::getOrderNo,no)
            .orderByDesc(TradeOrderStatusLog::getId).last("LIMIT 30")).stream()
            .map(t->new TimelineFact(t.getStatusType(),t.getFromStatus(),t.getToStatus(),time(t.getCreatedTime()))).toList();
    }
    public DisputeFact queryDispute(AgentActor actor,String no) {
        if(actor==null || no==null || no.length()>64)throw denied();
        Dispute d=disputes.selectOne(new LambdaQueryWrapper<Dispute>().eq(Dispute::getDisputeNo,no));
        if(d==null || !owns(actor,d.getBuyerId(),d.getSellerId()))throw denied();
        require(actor,d.getOrderNo());
        return new DisputeFact(d.getDisputeNo(),d.getOrderNo(),d.getStatus(),time(d.getCreatedTime()),time(d.getUpdatedTime()));
    }
    public List<EvidenceFact> queryDeliveryEvidenceSummary(AgentActor actor,String no) {
        require(actor,no);
        return evidence.selectList(new LambdaQueryWrapper<DeliveryEvidence>().select(DeliveryEvidence::getEvidenceType,DeliveryEvidence::getCreatedTime)
            .eq(DeliveryEvidence::getOrderNo,no).orderByDesc(DeliveryEvidence::getId).last("LIMIT 10")).stream()
            .map(e->new EvidenceFact(e.getEvidenceType(),time(e.getCreatedTime()))).toList();
    }
}
