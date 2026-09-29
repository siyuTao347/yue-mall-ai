package com.example.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_fund_reconciliation_diff")
public class FundReconciliationDiff {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String diffKey;
    private String diffType;
    private String bizNo;
    private BigDecimal expectedAmount;
    private BigDecimal actualAmount;
    private String status;
    private LocalDateTime createdTime;
}
