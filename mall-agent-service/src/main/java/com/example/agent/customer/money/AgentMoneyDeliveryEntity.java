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
@TableName("t_agent_money_delivery")
public class AgentMoneyDeliveryEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String deliveryNo;
    private String caseNo;
    private Long projectionRevision;
    private Long recipientUserId;
    private String deliveryKind;
    private String sessionNo;
    private String messageNo;
    private Long expectedControlEpoch;
    private String state; // PENDING, DELIVERED, HELD_FOR_STAFF, WAITING_SESSION, FAILED
    private String payloadHash;
    private LocalDateTime nextAttemptAt;
    private String lastError;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @Version
    private Long version;
}

