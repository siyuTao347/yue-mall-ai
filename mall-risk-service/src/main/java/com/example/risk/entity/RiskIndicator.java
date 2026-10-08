package com.example.risk.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("t_risk_indicator")
public class RiskIndicator {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String metricCode;
    private String subjectType;
    private Long subjectId;
    private String windowType;
    private LocalDateTime windowStart;
    private LocalDateTime windowEnd;
    private BigDecimal metricValue;
    private LocalDateTime updatedTime;
}
