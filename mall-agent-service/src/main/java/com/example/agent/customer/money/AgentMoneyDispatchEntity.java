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
@TableName("t_agent_money_dispatch")
public class AgentMoneyDispatchEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String dispatchNo;
    private Long userId;
    private String sessionNo;
    private String runNo;
    private String intentInstanceId;
    private String action;
    private String requestId;
    private String requestHash;
    private String payloadJson;
    private String grantRef;
    private String state;
    private String remoteTaskNo;
    private String receiptJson;
    private Integer attempts;
    private LocalDateTime nextAttemptAt;
    private LocalDateTime leaseUntil;
    private String leaseOwner;
    private Long fencingToken;
    private String lastError;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @Version
    private Long version;
}

