package com.example.risk.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_risk_rule")
public class RiskRule {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String ruleCode;
    private String ruleName;
    private String scene;
    private String expressionJson;
    private String action;
    private Integer riskScore;
    private Boolean autoCase;
    private Boolean enabled;
    private Integer version;
    private String remark;
    private LocalDateTime createdTime;
    private LocalDateTime updatedTime;
}
