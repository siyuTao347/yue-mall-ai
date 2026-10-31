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
@TableName("t_money_action_proposal")
public class MoneyActionProposalEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String proposalNo;
    private String caseNo;
    private String reportNo;
    private String actionType;
    private String targetRef;
    private String paramsJson;
    private String paramsHash;
    private String evidenceFingerprint;
    private String expectedVersionsJson;
    private String state;
    private String proposerRef;
    private Long reviewerId;
    private String reviewReason;
    private LocalDateTime reviewedAt;
    private LocalDateTime expiresAt;
    private String operationId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @Version
    private Long version;
}

