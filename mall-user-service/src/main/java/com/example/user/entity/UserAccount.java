package com.example.user.entity;

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
@TableName("t_user_account")
public class UserAccount {
    @TableId
    private Long userId;
    private BigDecimal availableAmount;
    private BigDecimal pendingSettleAmount;
    private BigDecimal frozenAmount;
    private Integer version;
    private LocalDateTime createdTime;
    private LocalDateTime updatedTime;
}
