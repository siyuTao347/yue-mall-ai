package com.example.item.payment.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_payment_command")
public class PaymentCommand {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String commandNo;
    private String bizKey;
    private String commandType;
    private String payloadHash;
    private String payloadJson;
    private String status;
    private Integer retryCount;
    private String leaseToken;
    private LocalDateTime lockedUntil;
    private LocalDateTime nextExecuteAt;
    private LocalDateTime createdTime;
    private LocalDateTime updatedTime;
}
