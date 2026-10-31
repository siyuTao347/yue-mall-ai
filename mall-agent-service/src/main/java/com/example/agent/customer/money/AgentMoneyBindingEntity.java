package com.example.agent.customer.money;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_agent_money_binding")
public class AgentMoneyBindingEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String caseNo;
    private String taskNo;
    private String remoteContextId;
    private String orderNo;
    private String audienceScope;
    private String originSessionNo;
    private String currentSessionNo;
    private String grantRef;
    private String caseState;
    private String taskState;
    private String subscriptionState;
    private Long projectionRevision;
    private String projectionHash;
    private String projectionJson;
    private LocalDateTime nextPollAt;
    @Version
    private Long version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

