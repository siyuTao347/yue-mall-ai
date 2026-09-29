package com.example.risk.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_risk_subject")
public class RiskSubject {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String subjectType;
    private Long subjectId;
    private String riskStatus;
    private String riskLevel;
    private Integer riskScore;
    private String lastDecisionNo;
    private String riskReason;
    private LocalDateTime restrictedUntil;
    private LocalDateTime createdTime;
    private LocalDateTime updatedTime;
}
