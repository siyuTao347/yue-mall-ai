package com.example.moneyagent.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_money_input_request")
public class MoneyInputRequestEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String inputNo;
    private String caseNo;
    private String taskNo;
    private String audienceScope;
    private Integer inputVersion;
    private String state;
    private String schemaJson;
    private String answerJson;
    private String answerSourceRef;
    private LocalDateTime expiresAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @Version
    private Long version;
}

