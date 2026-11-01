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
@TableName("t_agent_money_card")
public class AgentMoneyCardEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String cardId;
    private String sessionNo;
    private Long userId;
    private String caseNo;
    private String inputNo;
    private String cardType; // money_investigation, money_input
    private Long revision;
    private Long projectionRevision;
    private String allowedActionsJson;
    private String publicCardJson;
    private String state; // EDITABLE, SUBMITTING, SUBMITTED, STALE, FAILED
    private LocalDateTime expiresAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @Version
    private Long version;
}

