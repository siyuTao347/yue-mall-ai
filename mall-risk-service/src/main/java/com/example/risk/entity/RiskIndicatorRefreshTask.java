package com.example.risk.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_risk_indicator_refresh_task")
public class RiskIndicatorRefreshTask {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String subjectType;
    private Long subjectId;
    private String metricCodesJson;
    private String status;
    private Integer retryCount;
    private Integer maxRetryCount;
    private LocalDateTime nextExecuteTime;
    private String lastError;
    private LocalDateTime createdTime;
    private LocalDateTime updatedTime;
}
