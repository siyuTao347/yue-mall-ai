package com.example.user.entity;

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
@TableName("t_fund_business_ref")
public class FundBusinessRef {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String transactionNo;
    /** ORDER / ATTEMPT / REFUND / OPERATION */
    private String refType;
    private String refNo;
    /** TRANSACTION_BIND / IDEMPOTENT_KEY / VERIFIED_BACKFILL */
    private String linkOrigin;
    private LocalDateTime createdAt;
}

