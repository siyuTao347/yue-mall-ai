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
@TableName("t_recon_diff")
public class ReconDiff {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String diffNo;
    private String diffKey;
    private String profileId;
    private String channelMerchantNo;
    private String accountId;
    private String reconLayer;
    private String diffType;
    private String severity;
    private String bizKey;
    private String orderNo;
    private String attemptNo;
    private String refundNo;
    private BigDecimal channelAmount;
    private BigDecimal localAmount;
    private String channelStatus;
    private String localStatus;
    private String firstSeenRunNo;
    private String lastSeenRunNo;
    private String status;
    private String waiveReason;
    private LocalDateTime firstSeenAt;
    private LocalDateTime lastSeenAt;
    private LocalDateTime resolvedAt;
    private LocalDateTime createdTime;
    private LocalDateTime updatedTime;
}
