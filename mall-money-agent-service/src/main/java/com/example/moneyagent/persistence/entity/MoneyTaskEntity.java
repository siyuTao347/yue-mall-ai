package com.example.moneyagent.persistence.entity;

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
@TableName("t_money_task")
public class MoneyTaskEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String taskNo;
    private String caseNo;
    private String contextId;
    private String executionGrantRef;
    private String executionScopeJson;
    private String executionScopeHash;
    private String state;
    private String waitReason;
    private String checkpointJson;
    private String inputFingerprint;
    private LocalDateTime nextRunAt;
    private String leaseOwner;
    private LocalDateTime leaseUntil;
    private Long fencingToken;
    private Integer planRounds;
    private Integer toolCalls;
    private Long inputTokens;
    private Long outputTokens;
    private Long costMicrounits;
    @Version
    private Long version;
    private String lastErrorCode;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime finishedAt;
}

