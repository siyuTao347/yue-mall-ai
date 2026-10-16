package com.example.item.reconciliation.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_recon_task")
public class ReconTask {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String taskNo;
    private String scopeId;
    private String profileId;
    private String channelMerchantNo;
    private String accountId;
    private String statementType;
    private LocalDate businessDate;
    private String mode;
    private LocalDateTime windowEnd;
    private String taskStatus;
    private String leaseToken;
    private LocalDateTime lockedUntil;
    private LocalDateTime createdTime;
    private LocalDateTime updatedTime;
}
