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
@TableName("t_money_grant")
public class MoneyGrantEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String grantNo;
    private String issuer;
    private String subjectType;
    private String subjectId;
    private String scopeJson;
    private String allowedActionsJson;
    private String allowedReceiversJson;
    private String policyVersion;
    private String state;
    private LocalDateTime expiresAt;
    private LocalDateTime revokedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @Version
    private Long version;
}

