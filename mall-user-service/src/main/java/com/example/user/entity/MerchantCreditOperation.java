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
@TableName("t_merchant_credit_operation")
public class MerchantCreditOperation {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String operationKey;
    private Long merchantId;
    private String operationType;
    private BigDecimal score;
    private LocalDateTime createdTime;
}
