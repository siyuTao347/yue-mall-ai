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
@TableName("t_merchant_deposit")
public class MerchantDeposit {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long merchantId;
    private BigDecimal totalAmount;
    private BigDecimal frozenAmount;
    private BigDecimal deductedAmount;
    private LocalDateTime updatedTime;
    private LocalDateTime createdTime;
}
