package com.example.moneyagent.tools;

import api.agent.money.*;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.stereotype.Component;

@Component
public class OrderMoneyQueryClient {

    @DubboReference(check = false, timeout = 5000)
    private MoneyOrderQueryDubboService orderQueryDubboService;

    public ResolveMoneySubjectResult resolveSubject(MoneyAccessContext access, MoneySubjectSelector selector) {
        return orderQueryDubboService.resolveSubject(access, selector);
    }

    public MoneyOrderSnapshot getSnapshot(MoneyAccessContext access, MoneySnapshotQuery query) {
        return orderQueryDubboService.getSnapshot(access, query);
    }

    public MoneyChannelObservation observeChannel(MoneyAccessContext access, MoneyChannelQuery query) {
        return orderQueryDubboService.observeChannel(access, query);
    }

    public MoneyReconEvidence getReconEvidence(MoneyAccessContext access, MoneyReconQuery query) {
        return orderQueryDubboService.getReconEvidence(access, query);
    }
}

