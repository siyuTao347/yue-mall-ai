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
@TableName("t_asset_reservation")
public class AssetReservation {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String reservationNo;
    private String orderNo;
    private String idempotencyKey;
    private String snapshotJson;
    private Long itemId;
    private Long merchantId;
    private String assetType;
    private Integer quantity;
    private String cardSecretIds;
    private String status;
    private LocalDateTime expireTime;
    private LocalDateTime createdTime;
    private LocalDateTime updatedTime;
}
