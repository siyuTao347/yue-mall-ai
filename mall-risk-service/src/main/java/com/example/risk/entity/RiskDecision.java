package com.example.risk.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_risk_decision")
public class RiskDecision {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String decisionNo;
    private String eventNo;
    private String scene;
    private String bizType;
    private String bizNo;
    private String action;
    private Integer riskScore;
    private String riskLevel;
    private String hitRulesJson;
    private String metricSnapshotJson;
    private String evidenceJson;
    private String missingMetricsJson;
    private Boolean degraded;
    private LocalDateTime createdTime;
}
