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
@TableName("t_arbitration")
public class Arbitration {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String disputeNo;
    private String orderNo;
    private String result;
    private BigDecimal refundAmount;
    private BigDecimal releaseAmount;
    private BigDecimal depositDeductAmount;
    private String reason;
    private Long arbitratorId;
    private String appealStatus;
    private LocalDateTime createdTime;
}
