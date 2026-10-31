package com.example.moneyagent.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_money_tool_call")
public class MoneyToolCallEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String taskNo;
    private Integer callSeq;
    private String toolName;
    private String targetRef;
    private String inputHash;
    private String factRefJson;
    private String outcome;
    private String errorCode;
    private Integer latencyMs;
    private LocalDateTime createdAt;
}

