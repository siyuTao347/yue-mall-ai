package com.example.risk.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_risk_case")
public class RiskCase {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String caseNo;
    private String dedupKey;
    private String decisionNo;
    private String scene;
    private String bizType;
    private String bizNo;
    private String subjectType;
    private Long subjectId;
    private String riskLevel;
    private Integer riskScore;
    private String status;
    private Long assignedTo;
    private String resolvedAction;
    private String resolveReason;
    private LocalDateTime resolvedTime;
    private String lastCommandNo;
    private String lastCommandJson;
    private String commandStatus;
    private String commandLastError;
    private LocalDateTime commandSentTime;
    private LocalDateTime commandFinishedTime;
    private Integer commandRetryCount;
    private Long operationVersion;
    private Integer reopenCount;
    private LocalDateTime createdTime;
    private LocalDateTime updatedTime;
}
