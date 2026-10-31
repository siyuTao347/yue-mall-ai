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
@TableName("t_money_projection")
public class MoneyProjectionEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String caseNo;
    private String audienceScope;
    private Long revision;
    private String reportNo;
    private String publicJson;
    private String contentHash;
    private Long latestCaseEventSeq;
    private String policyVersion;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @Version
    private Long version;
}

