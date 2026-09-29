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
@TableName("t_fund_transaction")
public class FundTransaction {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String transactionNo;
    private String businessType;
    private String orderNo;
    private String withdrawNo;
    private Long merchantId;
    private Long userId;
    private BigDecimal amount;
    private String status;
    private String idempotencyKey;
    private String remark;
    private LocalDateTime createdTime;
}
