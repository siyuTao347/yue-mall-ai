package com.example.item.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_trade_orchestration_task")
public class TradeOrchestrationTask {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String taskNo;
    private String taskType;
    private String bizType;
    private String bizNo;
    private String status;
    private String contextJson;
    private String idempotencyKey;
    private Integer retryCount;
    private Integer maxRetryCount;
    private LocalDateTime nextExecuteTime;
    private String lockedBy;
    private LocalDateTime lockedUntil;
    private String traceId;
    private LocalDateTime createdTime;
    private LocalDateTime updatedTime;
}
