package com.example.moneyagent.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_money_report")
public class MoneyReportEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String reportNo;
    private String caseNo;
    private String taskNo;
    private Long revision;
    private Integer schemaVersion;
    private String basedOnFingerprint;
    private String internalJson;
    private String reportHash;
    private String outcome;
    private String ruleVersion;
    private String promptVersion;
    private String modelVersion;
    private LocalDateTime createdAt;
}

