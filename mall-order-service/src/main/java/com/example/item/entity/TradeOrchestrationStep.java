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
@TableName("t_trade_orchestration_step")
public class TradeOrchestrationStep {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String taskNo;
    private Integer stepNo;
    private String stepName;
    private String stepType;
    private String status;
    private String idempotencyKey;
    private String requestJson;
    private String responseJson;
    private Integer attemptCount;
    private Integer maxAttemptCount;
    private LocalDateTime nextExecuteTime;
    private String lastError;
    private LocalDateTime createdTime;
    private LocalDateTime updatedTime;
}
