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
@TableName("t_merchant_credit")
public class MerchantCredit {
    @TableId(type = IdType.INPUT)
    private Long merchantId;
    private Integer totalOrderCount;
    private Integer completedOrderCount;
    private Integer refundOrderCount;
    private Integer disputeOrderCount;
    private BigDecimal avgScore;
    private Integer creditScore;
    private LocalDateTime updatedTime;
    private LocalDateTime createdTime;
}
