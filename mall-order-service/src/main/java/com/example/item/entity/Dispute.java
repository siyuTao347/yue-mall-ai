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
@TableName("t_dispute")
public class Dispute {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String disputeNo;
    private String orderNo;
    private Long buyerId;
    private Long sellerId;
    private String disputeType;
    private String reason;
    private BigDecimal proposedRefundAmount;
    private String status;
    private LocalDateTime deadlineTime;
    private LocalDateTime createdTime;
    private LocalDateTime updatedTime;
}
