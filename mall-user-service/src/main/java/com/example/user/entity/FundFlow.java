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
@TableName("t_fund_flow")
public class FundFlow {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String transactionNo;
    private String ownerType;
    private Long ownerId;
    private String accountType;
    private String direction;
    private BigDecimal amount;
    private BigDecimal balanceAfter;
    private String memo;
    private LocalDateTime createdTime;
}
