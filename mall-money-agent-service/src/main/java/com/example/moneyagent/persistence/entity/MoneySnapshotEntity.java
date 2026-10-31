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
@TableName("t_money_snapshot")
public class MoneySnapshotEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String snapshotNo;
    private String taskNo;
    private Integer stepNo;
    private String sourceKind;
    private String fingerprint;
    private String coverageJson;
    private String factsJson;
    private LocalDateTime observedAt;
    private LocalDateTime createdAt;
}

