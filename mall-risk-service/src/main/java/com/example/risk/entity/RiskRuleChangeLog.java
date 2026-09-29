package com.example.risk.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_risk_rule_change_log")
public class RiskRuleChangeLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long ruleId;
    private String ruleCode;
    private String changeType;
    private String beforeSnapshot;
    private String afterSnapshot;
    private Long operatorId;
    private String operatorName;
    private String reason;
    private LocalDateTime createdTime;
}
