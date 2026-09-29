package com.example.item.entity;

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
@TableName("t_order_settlement")
public class OrderSettlement {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String orderNo;
    private BigDecimal orderAmount;
    private BigDecimal feeAmount;
    private BigDecimal sellerIncome;
    private String status;
    private String fundTransactionNo;
    private LocalDateTime settledTime;
    private LocalDateTime createdTime;
    private LocalDateTime updatedTime;
}
