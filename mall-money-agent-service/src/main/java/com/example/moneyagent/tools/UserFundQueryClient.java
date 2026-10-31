package com.example.moneyagent.tools;

import api.agent.money.*;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.stereotype.Component;

@Component
public class UserFundQueryClient {

    @DubboReference(check = false, timeout = 5000)
    private MoneyFundQueryDubboService fundQueryDubboService;

    public MoneyFundSnapshot getSnapshot(MoneyAccessContext access, MoneyFundQuery query) {
        return fundQueryDubboService.getSnapshot(access, query);
    }
}

