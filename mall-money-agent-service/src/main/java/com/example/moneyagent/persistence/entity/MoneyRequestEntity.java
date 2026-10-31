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
@TableName("t_money_request")
public class MoneyRequestEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String callerId;
    private String action;
    private String requestId;
    private String requestHash;
    private String payloadJson;
    private String subjectScopeHash;
    private String grantRef;
    private String caseNo;
    private String taskNo;
    private String state;
    private String receiptJson;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

