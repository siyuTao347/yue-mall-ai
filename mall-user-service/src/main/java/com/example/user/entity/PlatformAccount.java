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
@TableName("t_platform_account")
public class PlatformAccount {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String accountCode;
    private BigDecimal escrowAmount;
    private BigDecimal revenueAmount;
    private BigDecimal withdrawPendingAmount;
    private BigDecimal depositAmount;
    private Integer version;
    private LocalDateTime updatedTime;
    private LocalDateTime createdTime;
}
