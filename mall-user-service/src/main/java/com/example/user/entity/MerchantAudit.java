package com.example.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_merchant_audit")
public class MerchantAudit {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long merchantId;
    private String action;
    private Long auditorId;
    private String reason;
    private LocalDateTime createdTime;
}
