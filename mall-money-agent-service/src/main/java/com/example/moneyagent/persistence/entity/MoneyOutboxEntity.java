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
@TableName("t_money_outbox")
public class MoneyOutboxEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String eventNo;
    private String destination;
    private String payloadJson;
    private String state;
    private Integer attemptCount;
    private LocalDateTime nextAttemptAt;
    private String leaseOwner;
    private LocalDateTime leaseUntil;
    private Long fencingToken;
    private String lastError;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @Version
    private Long version;
}

