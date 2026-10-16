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
@TableName("t_recon_match")
public class ReconMatch {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String runNo;
    private String matchType;
    private Long channelItemId;
    private Long localItemId;
    private String bizKey;
    private BigDecimal channelAmount;
    private BigDecimal localAmount;
    private String matchStatus;
    private Integer ruleVersion;
    private LocalDateTime createdTime;
}
