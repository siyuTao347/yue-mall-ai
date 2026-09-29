package com.example.risk.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_risk_case_note")
public class RiskCaseNote {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String caseNo;
    private String noteType;
    private Long operatorId;
    private String operatorName;
    private String content;
    private String beforeStatus;
    private String afterStatus;
    private String evidenceJson;
    private LocalDateTime createdTime;
}
