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
@TableName("t_withdraw_request")
public class WithdrawRequest {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String withdrawNo;
    private Long merchantId;
    private Long userId;
    private BigDecimal amount;
    private String mockAccount;
    private String status;
    private Long auditAdminId;
    private String auditReason;
    private String mockPayoutNo;
    private LocalDateTime createdTime;
    private LocalDateTime updatedTime;
}
