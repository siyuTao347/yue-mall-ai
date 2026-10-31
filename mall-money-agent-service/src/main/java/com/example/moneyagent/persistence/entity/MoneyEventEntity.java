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
@TableName("t_money_event")
public class MoneyEventEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String eventNo;
    private String caseNo;
    private Long caseSeq;
    private String taskNo;
    private String eventType;
    private String payloadRefJson;
    private String visibility;
    private LocalDateTime createdAt;
}

