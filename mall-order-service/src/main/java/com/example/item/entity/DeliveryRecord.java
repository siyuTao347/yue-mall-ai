package com.example.item.entity;

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
@TableName("t_delivery_record")
public class DeliveryRecord {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String orderNo;
    private String deliveryType;
    private String deliveryContent;
    private String cardSecretIds;
    private String status;
    private LocalDateTime deliveredTime;
    private LocalDateTime firstViewTime;
    private LocalDateTime confirmedTime;
}
