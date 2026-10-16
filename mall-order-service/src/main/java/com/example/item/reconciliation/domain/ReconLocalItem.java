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
@TableName("t_recon_local_item")
public class ReconLocalItem {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String runNo;
    private String financialEventNo;
    private String itemType;
    private String bizNo;
    private String orderNo;
    private BigDecimal amount;
    private BigDecimal feeAmount;
    private String currency;
    private String status;
    private LocalDateTime occurredAt;
    private LocalDateTime createdTime;
}
