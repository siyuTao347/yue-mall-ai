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
@TableName("t_trade_order")
public class TradeOrder {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String orderNo;
    private Long buyerId;
    private Long sellerId;
    private Long merchantId;
    private Long itemId;
    private String itemSnapshot;
    private Integer quantity;
    private BigDecimal orderAmount;
    private BigDecimal feeAmount;
    private BigDecimal sellerIncome;
    private String orderStatus;
    private String payStatus;
    private String deliveryStatus;
    private String escrowStatus;
    private String disputeStatus;
    private String paymentNo;
    private LocalDateTime payDeadline;
    private LocalDateTime deliveryDeadline;
    private LocalDateTime deliveredTime;
    private LocalDateTime confirmedTime;
    private LocalDateTime autoConfirmTime;
    private LocalDateTime settleAvailableTime;
    private LocalDateTime settledTime;
    private Integer version;
    private LocalDateTime createdTime;
    private LocalDateTime updatedTime;
}
