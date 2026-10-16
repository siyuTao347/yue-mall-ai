package com.example.item.reconciliation.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_recon_run")
public class ReconRun {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String runNo;
    private String taskNo;
    private Integer runSeq;
    private Long fileId;
    private String fileHash;
    private LocalDateTime snapshotCutoff;
    private Integer ruleVersion;
    private Integer channelCount;
    private Integer localCount;
    private Integer matchedCount;
    private Integer diffCount;
    private BigDecimal channelAmount;
    private BigDecimal localAmount;
    private BigDecimal matchedAmount;
    private BigDecimal diffAmount;
    private String executionStatus;
    private String outcome;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private LocalDateTime createdTime;
}
